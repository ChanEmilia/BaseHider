package io.github.chanemilia.baseHider;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;

public class WorldConfig {
    public final boolean enabled;
    public final BlockData replacementBlock;
    public final int blockHideY;
    public final int showDistanceSq;
    public final int showY;

    public WorldConfig(ConfigurationSection section) {
        this.enabled = section.getBoolean("enabled", false);

        Material mat = Material.getMaterial(section.getString("replacement-block", "DEEPSLATE"));
        this.replacementBlock = (mat != null ? mat : Material.DEEPSLATE).createBlockData();

        this.blockHideY = section.getInt("block-hide-y", 0);

        int dist = section.getInt("show-distance", 64);
        this.showDistanceSq = dist * dist;

        this.showY = section.getInt("show-y", 25);
    }
}