package com.bhautik.mcagent.item;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Curated composite goals (PRD UC-09): one friendly name expands into
 * several related items whose shared dependencies are gathered once.
 */
public final class Kits {

    private static final Map<String, List<String>> KITS = Map.of(
            "iron_armor", List.of(
                    "minecraft:iron_helmet", "minecraft:iron_chestplate",
                    "minecraft:iron_leggings", "minecraft:iron_boots"),
            "diamond_armor", List.of(
                    "minecraft:diamond_helmet", "minecraft:diamond_chestplate",
                    "minecraft:diamond_leggings", "minecraft:diamond_boots"),
            "iron_tools", List.of(
                    "minecraft:iron_pickaxe", "minecraft:iron_sword",
                    "minecraft:iron_axe", "minecraft:iron_shovel"),
            "diamond_tools", List.of(
                    "minecraft:diamond_pickaxe", "minecraft:diamond_sword",
                    "minecraft:diamond_axe", "minecraft:diamond_shovel")
    );

    private Kits() {
    }

    /** Piece ids for a kit name ("diamond_armor"), if it exists. */
    public static Optional<List<String>> itemsFor(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(KITS.get(rawName.trim().toLowerCase()));
    }

    public static boolean isKit(String rawName) {
        return itemsFor(rawName).isPresent();
    }

    /** All known kit names, for help text and validation. */
    public static java.util.Set<String> names() {
        return KITS.keySet();
    }
}
