package io.github.chanemilia.baseHider;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

// Hiya programming enthusiast ^_^
public class HiderSystem implements Listener {

    private final BaseHider plugin;
    private final ProtocolManager protocolManager;
    private final Map<String, WorldConfig> worldConfigs = new HashMap<>();

    private final Map<String, Boolean> currentStates = new ConcurrentHashMap<>();
    private final Map<Long, SectionCache> packetCache = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<PendingUpdate> updateQueue = new PriorityBlockingQueue<>();
    private final Set<String> pendingKeys = Collections.synchronizedSet(new HashSet<>());

    private static final Map<Material, SectionCache> solidCache = new ConcurrentHashMap<>();

    private int updatesPerTick;
    private int rescanInterval;

    private final List<BukkitTask> tasks = new ArrayList<>();

    private Class<?> sectionPosClass;
    private Method sectionPosOfMethod;

    public HiderSystem(BaseHider plugin) {
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();

        cacheReflection();
        loadConfig();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        registerChunkListener();

        startQueueProcessor();
        startCacheCleaner();
        startRescanTask();
    }

    /**
     * Intercepts the MAP_CHUNK packet (ClientboundLevelChunkWithLightPacket) to hide the underground instantly
     * which ensures the client receives our fake block data packets immediately after the real chunk data,
     * prevents client from ever rendering the real block data when out of range
     */
    private void registerChunkListener() {
        protocolManager.addPacketListener(new PacketAdapter(plugin, PacketType.Play.Server.MAP_CHUNK) {
            @Override
            public void onPacketSending(PacketEvent event) {
                Player player = event.getPlayer();
                if (player == null || !player.isOnline()) return;

                WorldConfig config = worldConfigs.get(player.getWorld().getName());
                if (config == null) return;

                PacketContainer packet = event.getPacket();
                int chunkX = packet.getIntegers().read(0);
                int chunkZ = packet.getIntegers().read(1);

                // Bukkit API mandates that we switch to the main thread to get the ChunkSnapshot safely
                // We only switch back to async to scan the snapshot (to save our cpu
                Bukkit.getScheduler().runTask(plugin, () ->
                        processNewChunk(player, chunkX, chunkZ, config)
                );
            }
        });
    }

    private void processNewChunk(Player player, int cx, int cz, WorldConfig config) {
        if (!player.isOnline()) return;

        Location loc = player.getLocation();
        double pY = loc.getY();
        if (pY < config.showY) return;

        int minSection = player.getWorld().getMinHeight() >> 4;
        int maxSection = Math.min(player.getWorld().getMaxHeight() >> 4, config.blockHideY >> 4);

        double showDistSq = config.showDistanceSq;

        for (int sy = minSection; sy <= maxSection; sy++) {
            double sx = (cx << 4) + 8;
            double syPos = (sy << 4) + 8;
            double sz = (cz << 4) + 8;
            double distSq = Math.pow(loc.getX() - sx, 2) + Math.pow(loc.getY() - syPos, 2) + Math.pow(loc.getZ() - sz, 2);

            if (distSq <= showDistSq) {
                long sectionKey = getSectionKey(cx, cz, sy);
                String stateKey = player.getUniqueId().toString() + "_" + sectionKey;

                if (currentStates.containsKey(stateKey)) {
                    currentStates.put(stateKey, false);
                }
                continue;
            }

            long sectionKey = getSectionKey(cx, cz, sy);
            String stateKey = player.getUniqueId().toString() + "_" + sectionKey;

            currentStates.put(stateKey, true);
            pendingKeys.add(stateKey);

            SectionCache solid = getSolidCache(config);

            PendingUpdate update = new PendingUpdate(player.getUniqueId(), cx, cz, sy, solid.shorts, solid.data, 0.0, stateKey);
            sendProtocolPacket(player, update);
            pendingKeys.remove(stateKey);
        }
    }

    /**
     * Hi, ProtocolLib is slightly broken in 1.21, at least at the time of writing
     *
     * The issue:
     * So basically, when we hide blocks, we send a MULTI_BLOCK_CHANGE packet,
     * but of course the packet needs to know which chunk section it is modifying
     * Minecraft expects a specific internal object called 'SectionPos'.
     * Usually ProtocolLib is supposed to handle this translation between the plugin
     * and Minecraft, (ie plugin gives ProtocolLib the coordinates and it creates
     * 'SectionPos' for me). However, 1.21 Paper altered the packet structure slightly
     * which results in ProtocolLib being unable to create that 'SectionPos' object
     * automatically using its standard wrappers (BlockPosition), putting 'null' into
     * the packet instead. When the server tries to send packet and sees 'null', it
     * throws a NullPointerException and kicks the player. Which is.. bad
     *
     * The solution:
     * Since ProtocolLib's automatic tool is broken, we have to unfortunately manually
     * built the part using :sparkles: Reflection :sparkles:!
     *   - Learn more @ https://docs.oracle.com/javase/tutorial/reflect/
     *     comprehensive but kind of a dry read
     */
    private void cacheReflection() {
        try {
            // Anyway, we locate the class here
            this.sectionPosClass = Class.forName("net.minecraft.core.SectionPos");
            // Find the section position method inside that class
            this.sectionPosOfMethod = this.sectionPosClass.getMethod("of", long.class);
            // See plugin's sendProtocolPacket method for next step
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to cache SectionPos reflection: " + e.getMessage());
        }
    }

    private void loadConfig() {
        int updatesPerSecond = plugin.getConfig().getInt("performance.max-updates-per-second", 100);
        this.updatesPerTick = Math.max(1, updatesPerSecond / 20);
        this.rescanInterval = plugin.getConfig().getInt("performance.rescan-interval", 30);

        for (String key : plugin.getConfig().getKeys(false)) {
            if (key.equalsIgnoreCase("performance") || key.equalsIgnoreCase("enabled")) continue;

            ConfigurationSection section = plugin.getConfig().getConfigurationSection(key);
            if (section != null && section.getBoolean("enabled")) {
                worldConfigs.put(key, new WorldConfig(section));
            }
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();

        if (from.getBlockX() >> 4 == to.getBlockX() >> 4 &&
                from.getBlockZ() >> 4 == to.getBlockZ() >> 4 &&
                from.distanceSquared(to) < 4) {
            return;
        }

        Player player = event.getPlayer();
        WorldConfig config = worldConfigs.get(player.getWorld().getName());
        if (config == null) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> whatWouldEmiliaDo(player, config));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cleanup(event.getPlayer());
    }

    private void startRescanTask() {
        tasks.add(new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    WorldConfig cfg = worldConfigs.get(p.getWorld().getName());
                    if (cfg != null) {
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> whatWouldEmiliaDo(p, cfg));
                    }
                }
            }
        }.runTaskTimer(plugin, rescanInterval, rescanInterval));
    }

    private void startQueueProcessor() {
        tasks.add(new BukkitRunnable() {
            @Override
            public void run() {
                int budget = updatesPerTick;
                while (budget > 0 && !updateQueue.isEmpty()) {
                    PendingUpdate update = updateQueue.poll();
                    if (update == null) continue;
                    pendingKeys.remove(update.uniqueKey);

                    Player p = Bukkit.getPlayer(update.playerUUID);
                    if (p != null && p.isOnline()) {
                        sendProtocolPacket(p, update);
                    }
                    budget--;
                }
            }
        }.runTaskTimer(plugin, 1, 1));
    }

    private void startCacheCleaner() {
        tasks.add(new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                packetCache.entrySet().removeIf(e -> (now - e.getValue().lastAccessed) > 300000);
            }
        }.runTaskTimerAsynchronously(plugin, 1200, 1200));
    }

    private void sendProtocolPacket(Player player, PendingUpdate update) {
        if (update.shorts == null || update.shorts.length == 0) return;

        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.MULTI_BLOCK_CHANGE);

        long sectionPosLong = ((long)(update.chunkX & 0x3FFFFF) << 42)
                | ((long)(update.chunkZ & 0x3FFFFF) << 20)
                | (long)(update.sectionY & 0xFFFFF);

        try {
            if (sectionPosClass == null) cacheReflection();
            // Create SectionPos method ourselves
            Object nmsSectionPos = sectionPosOfMethod.invoke(null, sectionPosLong);
            // Jam our object into the packet
            packet.getModifier().write(0, nmsSectionPos);
        } catch (Exception e) {
            return;
        }

        packet.getShortArrays().write(0, update.shorts);
        packet.getBlockDataArrays().write(0, update.data);

        try {
            protocolManager.sendServerPacket(player, packet);
        } catch (OutOfMemoryError e) {
            updateQueue.clear();
            System.gc();
        }
    }

    private void whatWouldEmiliaDo(Player player, WorldConfig config) {
        if (!player.isOnline()) return;

        Location loc = player.getLocation();
        int centerX = loc.getBlockX() >> 4;
        int centerZ = loc.getBlockZ() >> 4;
        int pY = loc.getBlockY();

        int viewDist = player.getClientViewDistance();
        double showDistSq = config.showDistanceSq;
        boolean globalReveal = pY < config.showY;

        int minSection = player.getWorld().getMinHeight() >> 4;
        int maxSection = Math.min(player.getWorld().getMaxHeight() >> 4, config.blockHideY >> 4);

        for (int x = -viewDist; x <= viewDist; x++) {
            for (int z = -viewDist; z <= viewDist; z++) {
                int cx = centerX + x;
                int cz = centerZ + z;

                if (x*x + z*z > viewDist * viewDist) continue;

                for (int sy = minSection; sy <= maxSection; sy++) {
                    long sectionKey = getSectionKey(cx, cz, sy);
                    String stateKey = player.getUniqueId().toString() + "_" + sectionKey;

                    double sx = (cx << 4) + 8;
                    double syPos = (sy << 4) + 8;
                    double sz = (cz << 4) + 8;
                    double distSq = Math.pow(loc.getX() - sx, 2) + Math.pow(loc.getY() - syPos, 2) + Math.pow(loc.getZ() - sz, 2);

                    boolean shouldHide = !globalReveal && distSq > showDistSq;
                    Boolean isHidden = currentStates.getOrDefault(stateKey, false);

                    if (shouldHide != isHidden) {
                        if (pendingKeys.contains(stateKey)) continue;

                        currentStates.put(stateKey, shouldHide);
                        pendingKeys.add(stateKey);

                        queueUpdate(player.getUniqueId(), cx, cz, sy, config, shouldHide, distSq, stateKey, sectionKey);
                    }
                }
            }
        }
    }

    private void queueUpdate(UUID uuid, int cx, int cz, int sy, WorldConfig config, boolean hide, double distSq, String uniqueKey, long sectionKey) {
        if (hide) {
            SectionCache solid = getSolidCache(config);
            updateQueue.add(new PendingUpdate(uuid, cx, cz, sy, solid.shorts, solid.data, distSq, uniqueKey));
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline() || !p.getWorld().isChunkLoaded(cx, cz)) {
                pendingKeys.remove(uniqueKey);
                return;
            }

            ChunkSnapshot snapshot = p.getWorld().getChunkAt(cx, cz).getChunkSnapshot(false, false, false);

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                ScanResult result = scanSection(snapshot, sy, config);

                if (result != null) {
                    updateQueue.add(new PendingUpdate(uuid, cx, cz, sy, result.shorts, result.data, distSq, uniqueKey));
                } else {
                    pendingKeys.remove(uniqueKey);
                }
            });
        });
    }

    private SectionCache getSolidCache(WorldConfig config) {
        return solidCache.computeIfAbsent(config.replacementBlock.getMaterial(), k -> {
            ScanResult res = emiliasPaintBrush(config);
            if (res == null) return new SectionCache(new short[0], new WrappedBlockData[0]);
            return new SectionCache(res.shorts, res.data);
        });
    }

    private ScanResult scanSection(ChunkSnapshot snapshot, int sy, WorldConfig config) {
        int startY = sy << 4;

        List<Short> shortList = new ArrayList<>();
        List<WrappedBlockData> dataList = new ArrayList<>();

        Material replacementMat = config.replacementBlock.getMaterial();

        for (int y = 0; y < 16; y++) {
            int absY = startY + y;
            if (absY > config.blockHideY) continue;

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockData actualData = snapshot.getBlockData(x, absY, z);

                    if (actualData.getMaterial() != replacementMat) {
                        short local = (short) ((x << 8) | (z << 4) | y);
                        shortList.add(local);
                        dataList.add(WrappedBlockData.createData(actualData));
                    }
                }
            }
        }

        if (shortList.isEmpty()) return null;

        short[] shorts = new short[shortList.size()];
        WrappedBlockData[] data = new WrappedBlockData[dataList.size()];
        for (int i = 0; i < shortList.size(); i++) {
            shorts[i] = shortList.get(i);
            data[i] = dataList.get(i);
        }

        return new ScanResult(shorts, data);
    }

    /**
     * Generates a 16 wide cubic block array of the replacement block
     * Because this uses primitive array filling, it is effectively O(1)
     * at least compared to reading World memory!
     * Avoids main-thread locking or disk I/O
     */
    private ScanResult emiliasPaintBrush(WorldConfig config) {
        int size = 4096;
        short[] shorts = new short[size];
        WrappedBlockData[] data = new WrappedBlockData[size];

        WrappedBlockData replacement = WrappedBlockData.createData(config.replacementBlock);

        int i = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    shorts[i] = (short) ((x << 8) | (z << 4) | y);
                    data[i] = replacement;
                    i++;
                }
            }
        }

        return new ScanResult(shorts, data);
    }

    public void cleanup(Player player) {
        String uid = player.getUniqueId().toString();
        currentStates.keySet().removeIf(k -> k.startsWith(uid));
    }

    public void shutdown() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        packetCache.clear();
        updateQueue.clear();
        solidCache.clear();
    }

    private long getSectionKey(int x, int z, int y) {
        return ((long)(x & 0xFFFFFF) << 40) | ((long)(z & 0xFFFFFF) << 16) | (y & 0xFFFF);
    }

    private static class PendingUpdate implements Comparable<PendingUpdate> {
        final UUID playerUUID;
        final int chunkX, chunkZ, sectionY;
        final short[] shorts;
        final WrappedBlockData[] data;
        final double distSq;
        final String uniqueKey;

        PendingUpdate(UUID uid, int cx, int cz, int sy, short[] shorts, WrappedBlockData[] data, double dist, String key) {
            this.playerUUID = uid;
            this.chunkX = cx;
            this.chunkZ = cz;
            this.sectionY = sy;
            this.shorts = shorts;
            this.data = data;
            this.distSq = dist;
            this.uniqueKey = key;
        }

        @Override
        public int compareTo(PendingUpdate o) {
            return Double.compare(this.distSq, o.distSq);
        }
    }

    private static class SectionCache {
        final short[] shorts;
        final WrappedBlockData[] data;
        long lastAccessed;

        SectionCache(short[] s, WrappedBlockData[] d) {
            this.shorts = s;
            this.data = d;
            this.lastAccessed = System.currentTimeMillis();
        }
    }

    private static class ScanResult {
        final short[] shorts;
        final WrappedBlockData[] data;
        ScanResult(short[] s, WrappedBlockData[] d) { this.shorts = s; this.data = d; }
    }
}