package io.github.chanemilia.baseHider;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.*;

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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

public class HiderSystem extends PacketListenerAbstract implements Listener {

    private final BaseHider plugin;
    private final Map<String, WorldConfig> worldConfigs = new HashMap<>();

    private final Map<String, Boolean> currentStates = new ConcurrentHashMap<>();

    private final Set<String> hiddenEntities = Collections.synchronizedSet(new HashSet<>());

    private final PriorityBlockingQueue<PendingUpdate> updateQueue = new PriorityBlockingQueue<>();
    private final Set<String> pendingKeys = Collections.synchronizedSet(new HashSet<>());

    private static final Map<Material, SectionCache> solidCache = new ConcurrentHashMap<>();

    private GlobalConfig globalConfig;

    private final List<BukkitTask> tasks = new ArrayList<>();

    public HiderSystem(BaseHider plugin) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;

        loadConfig();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        PacketEvents.getAPI().getEventManager().registerListener(this);

        startQueueProcessor();
        startRescanTask();
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
            handleChunkData(event);
        } else if (isEntityPacket(event.getPacketType())) {
            handleEntityPacket(event);
        }
    }

    private boolean isEntityPacket(PacketTypeCommon type) {
        return type == PacketType.Play.Server.SPAWN_ENTITY ||
                type == PacketType.Play.Server.NAMED_SOUND_EFFECT ||
                type == PacketType.Play.Server.ENTITY_TELEPORT;
    }

    private void handleChunkData(PacketSendEvent event) {
        Player player = (Player) event.getPlayer();
        if (player == null || !player.isOnline()) return;

        WorldConfig config = worldConfigs.get(player.getWorld().getName());
        if (config == null) return;

        WrapperPlayServerChunkData packet = new WrapperPlayServerChunkData(event);
        Column column = packet.getColumn();
        int chunkX = column.getX();
        int chunkZ = column.getZ();

        Bukkit.getScheduler().runTask(plugin, () ->
                processNewChunk(player, chunkX, chunkZ, config)
        );
    }

    private void handleEntityPacket(PacketSendEvent event) {
        Player player = (Player) event.getPlayer();
        if (player == null || !player.isOnline()) return;

        WorldConfig config = worldConfigs.get(player.getWorld().getName());
        if (config == null || !config.hideEntities) return;

        int entityId;
        Location entLoc;

        if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY) {
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(event);
            entityId = spawn.getEntityId();
            entLoc = new Location(player.getWorld(), spawn.getPosition().getX(), spawn.getPosition().getY(), spawn.getPosition().getZ());
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_TELEPORT) {
            WrapperPlayServerEntityTeleport tp = new WrapperPlayServerEntityTeleport(event);
            entityId = tp.getEntityId();
            entLoc = new Location(player.getWorld(), tp.getPosition().getX(), tp.getPosition().getY(), tp.getPosition().getZ());
        } else {
            return;
        }

        if (entLoc.getBlockY() > config.blockHideY) return;
        if (player.getLocation().getY() < config.showY) return;

        double distSq = player.getLocation().distanceSquared(entLoc);

        if (distSq > config.showDistanceSq) {
            event.setCancelled(true);
            hiddenEntities.add(player.getUniqueId() + "_" + entityId);
        }
    }

    private void processNewChunk(Player player, int cx, int cz, WorldConfig config) {
        if (!player.isOnline()) return;

        Location loc = player.getLocation();
        double pX = loc.getX();
        double pY = loc.getY();
        double pZ = loc.getZ();

        if (pY < config.showY) return;

        int minSection = player.getWorld().getMinHeight() >> 4;
        int maxSection = Math.min(player.getWorld().getMaxHeight() >> 4, config.blockHideY >> 4);

        for (int sy = minSection; sy <= maxSection; sy++) {
            long sectionKey = getSectionKey(cx, cz, sy);
            String stateKey = player.getUniqueId().toString() + "_" + sectionKey;

            double sx = (cx << 4) + 8;
            double syPos = (sy << 4) + 8;
            double sz = (cz << 4) + 8;
            double distSq = Math.pow(pX - sx, 2) + Math.pow(pY - syPos, 2) + Math.pow(pZ - sz, 2);

            boolean shouldHide = distSq > config.showDistanceSq;

            if (shouldHide) {
                currentStates.put(stateKey, true);
                SectionCache solid = getSolidCache(config);
                PendingUpdate update = new PendingUpdate(player.getUniqueId(), cx, cz, sy, solid.blockInfo, 0.0, stateKey);
                sendProtocolPacket(player, update);
            }
        }
    }

    private void loadConfig() {
        this.globalConfig = new GlobalConfig(plugin.getConfig().getConfigurationSection("performance"));

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
        }.runTaskTimer(plugin, globalConfig.rescanInterval, globalConfig.rescanInterval));
    }

    private void startQueueProcessor() {
        tasks.add(new BukkitRunnable() {
            @Override
            public void run() {
                int budget = globalConfig.updatesPerTick;
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

    private void sendProtocolPacket(Player player, PendingUpdate update) {
        if (update.blockInfo == null || update.blockInfo.length == 0) return;

        WrapperPlayServerMultiBlockChange.EncodedBlock[] blocks = new WrapperPlayServerMultiBlockChange.EncodedBlock[update.blockInfo.length];

        for (int i = 0; i < update.blockInfo.length; i++) {
            SimpleBlockInfo info = update.blockInfo[i];
            blocks[i] = new WrapperPlayServerMultiBlockChange.EncodedBlock(info.globalId, info.x, info.y, info.z);
        }

        WrapperPlayServerMultiBlockChange packet = new WrapperPlayServerMultiBlockChange(
                new Vector3i(update.chunkX, update.sectionY, update.chunkZ),
                false,
                blocks
        );

        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
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
                        if (shouldHide && !globalConfig.rehideChunks) {
                            continue;
                        }

                        if (pendingKeys.contains(stateKey)) continue;

                        if (shouldHide) {
                            currentStates.put(stateKey, true);
                        } else {
                            currentStates.remove(stateKey);
                        }

                        pendingKeys.add(stateKey);
                        queueUpdate(player.getUniqueId(), cx, cz, sy, config, shouldHide, distSq, stateKey, sectionKey);
                    }
                }
            }
        }

        if (config.hideEntities) {
            updateEntityVisibility(player, config);
        }
    }

    private void updateEntityVisibility(Player player, WorldConfig config) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            int radius = (player.getClientViewDistance() + 2) * 16;

            for (org.bukkit.entity.Entity entity : player.getNearbyEntities(radius, 512, radius)) {
                if (entity.getEntityId() == player.getEntityId()) continue;

                String key = player.getUniqueId() + "_" + entity.getEntityId();
                boolean isCurrentlyHidden = hiddenEntities.contains(key);

                Location loc = entity.getLocation();
                boolean shouldHide = false;

                if (loc.getBlockY() <= config.blockHideY && player.getLocation().getY() >= config.showY) {
                    if (player.getLocation().distanceSquared(loc) > config.showDistanceSq) {
                        shouldHide = true;
                    }
                }

                if (isCurrentlyHidden && !shouldHide) {
                    player.hideEntity(plugin, entity);
                    player.showEntity(plugin, entity);
                    hiddenEntities.remove(key);
                }
                else if (!isCurrentlyHidden && shouldHide) {
                    WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(entity.getEntityId());
                    PacketEvents.getAPI().getPlayerManager().sendPacket(player, destroy);
                    hiddenEntities.add(key);
                }
            }
        });
    }

    private void queueUpdate(UUID uuid, int cx, int cz, int sy, WorldConfig config, boolean hide, double distSq, String uniqueKey, long sectionKey) {
        if (hide) {
            SectionCache solid = getSolidCache(config);
            updateQueue.add(new PendingUpdate(uuid, cx, cz, sy, solid.blockInfo, distSq, uniqueKey));
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
                SimpleBlockInfo[] result = scanSection(snapshot, sy, config);

                if (result != null) {
                    updateQueue.add(new PendingUpdate(uuid, cx, cz, sy, result, distSq, uniqueKey));
                } else {
                    pendingKeys.remove(uniqueKey);
                }
            });
        });
    }

    private SectionCache getSolidCache(WorldConfig config) {
        return solidCache.computeIfAbsent(config.replacementBlock.getMaterial(), k -> {
            SimpleBlockInfo[] res = emiliasPaintBrush(config);
            return new SectionCache(res);
        });
    }

    private SimpleBlockInfo[] scanSection(ChunkSnapshot snapshot, int sy, WorldConfig config) {
        int startY = sy << 4;
        List<SimpleBlockInfo> infoList = new ArrayList<>();
        Material replacementMat = config.replacementBlock.getMaterial();

        for (int y = 0; y < 16; y++) {
            int absY = startY + y;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockData actualData = snapshot.getBlockData(x, absY, z);

                    if (actualData.getMaterial() != replacementMat) {
                        int globalId = SpigotConversionUtil.fromBukkitBlockData(actualData).getGlobalId();
                        infoList.add(new SimpleBlockInfo(globalId, x, y, z));
                    }
                }
            }
        }

        if (infoList.isEmpty()) return null;
        return infoList.toArray(new SimpleBlockInfo[0]);
    }

    private SimpleBlockInfo[] emiliasPaintBrush(WorldConfig config) {
        int size = 4096;
        SimpleBlockInfo[] infos = new SimpleBlockInfo[size];

        int globalId = SpigotConversionUtil.fromBukkitBlockData(config.replacementBlock).getGlobalId();

        int i = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    infos[i] = new SimpleBlockInfo(globalId, x, y, z);
                    i++;
                }
            }
        }
        return infos;
    }

    public void cleanup(Player player) {
        String uid = player.getUniqueId().toString();
        currentStates.keySet().removeIf(k -> k.startsWith(uid));
        hiddenEntities.removeIf(k -> k.startsWith(uid));
    }

    public void shutdown() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        updateQueue.clear();
        solidCache.clear();
        hiddenEntities.clear();
    }

    private long getSectionKey(int x, int z, int y) {
        return ((long)(x & 0xFFFFFF) << 40) | ((long)(z & 0xFFFFFF) << 16) | (y & 0xFFFF);
    }

    private static class SimpleBlockInfo {
        final int globalId;
        final int x, y, z;
        SimpleBlockInfo(int id, int x, int y, int z) {
            this.globalId = id;
            this.x = x; this.y = y; this.z = z;
        }
    }

    private record PendingUpdate(UUID playerUUID, int chunkX, int chunkZ, int sectionY, SimpleBlockInfo[] blockInfo,
                                 double distSq, String uniqueKey) implements Comparable<PendingUpdate> {

        @Override
            public int compareTo(PendingUpdate o) {
                return Double.compare(this.distSq, o.distSq);
            }
        }

    private static class SectionCache {
        final SimpleBlockInfo[] blockInfo;
        SectionCache(SimpleBlockInfo[] data) { this.blockInfo = data; }
    }
}