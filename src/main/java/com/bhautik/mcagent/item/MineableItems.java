package com.bhautik.mcagent.item;

import java.util.Map;
import java.util.Optional;

/**
 * Maps obtainable items to the block that yields them when mined.
 * Directly-mineable items only; crafting and smelting come later.
 */
public final class MineableItems {
    private static final Map<String, String> ITEM_TO_SOURCE_BLOCK = Map.ofEntries(
            Map.entry("minecraft:cobblestone", "minecraft:stone"),
            Map.entry("minecraft:stone", "minecraft:stone"),
            Map.entry("minecraft:dirt", "minecraft:dirt"),
            Map.entry("minecraft:grass_block", "minecraft:grass_block"),
            Map.entry("minecraft:oak_log", "minecraft:oak_log"),
            Map.entry("minecraft:sand", "minecraft:sand"),
            Map.entry("minecraft:gravel", "minecraft:gravel"),
            Map.entry("minecraft:iron_ore", "minecraft:iron_ore"),
            Map.entry("minecraft:coal", "minecraft:coal_ore"),
            Map.entry("minecraft:coal_ore", "minecraft:coal_ore"),
            Map.entry("minecraft:netherrack", "minecraft:netherrack")
    );

    private MineableItems() {
    }

    public static Optional<String> sourceBlockFor(String itemId) {
        return Optional.ofNullable(ITEM_TO_SOURCE_BLOCK.get(itemId));
    }
}
