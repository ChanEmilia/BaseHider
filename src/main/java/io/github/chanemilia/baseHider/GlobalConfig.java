package io.github.chanemilia.baseHider;

import org.bukkit.configuration.ConfigurationSection;

public class GlobalConfig {
    public final int updatesPerTick;
    public final int rescanInterval;
    public final boolean rehideChunks;

    public GlobalConfig(ConfigurationSection section) {
        if (section == null) {
            this.updatesPerTick = 5;
            this.rescanInterval = 10;
            this.rehideChunks = true;
            return;
        }

        int updatesPerSecond = section.getInt("max-updates-per-second", 200);
        this.updatesPerTick = Math.max(25, updatesPerSecond / 20);
        this.rescanInterval = section.getInt("rescan-interval", 20);
        this.rehideChunks = section.getBoolean("rehide-chunks", false);
    }
}