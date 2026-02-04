package io.github.chanemilia.baseHider;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.Objects;

public class BaseHider extends JavaPlugin implements Listener, CommandExecutor {

    private HiderSystem hiderSystem;

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        PacketEvents.getAPI().init();

        saveDefaultConfig();

        hiderSystem = new HiderSystem(this);

        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("basehider") != null) {
            Objects.requireNonNull(getCommand("basehider")).setExecutor(this);
        }

        getLogger().info("Good job Emilia your plugin works!");
    }

    @Override
    public void onDisable() {
        PacketEvents.getAPI().terminate();

        if (hiderSystem != null) {
            hiderSystem.shutdown();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (hiderSystem != null) {
            hiderSystem.cleanup(event.getPlayer());
        }
    }

    public void reload() {
        if (hiderSystem != null) {
            hiderSystem.shutdown();
        }
        reloadConfig();
        hiderSystem = new HiderSystem(this);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("basehider.op")) {
                sender.sendMessage(Component.text("Insufficient permissions.", NamedTextColor.RED));
                return true;
            }
            reload();
            sender.sendMessage(Component.text("BaseHider config reloaded!", NamedTextColor.GREEN));
            return true;
        }
        return false;
    }
}