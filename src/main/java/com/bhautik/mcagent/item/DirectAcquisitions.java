package com.bhautik.mcagent.item;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Curated table of items the agent can acquire directly by having
 * Baritone mine/harvest a block — no crafting, smelting, or trading.
 *
 * Each entry records the source block and the pickaxe tier required for
 * the block to drop anything (gatherables are HAND). This keeps failures
 * honest: without the right tool the agent refuses up front instead of
 * mining blocks that drop nothing.
 */
public final class DirectAcquisitions {

    public enum ToolTier {
        HAND,
        WOOD,
        STONE,
        IRON,
        DIAMOND
    }

    private record Acquisition(String sourceBlock, ToolTier tool) {
    }

    /** Pickaxes grouped by minimum tier they satisfy (golden counts as wood). */
    private static final Map<ToolTier, Set<String>> PICKAXES_AT_OR_ABOVE = Map.of(
            ToolTier.WOOD, Set.of("minecraft:wooden_pickaxe", "minecraft:golden_pickaxe",
                    "minecraft:stone_pickaxe", "minecraft:iron_pickaxe",
                    "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe"),
            ToolTier.STONE, Set.of("minecraft:stone_pickaxe", "minecraft:iron_pickaxe",
                    "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe"),
            ToolTier.IRON, Set.of("minecraft:iron_pickaxe",
                    "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe"),
            ToolTier.DIAMOND, Set.of("minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe")
    );

    /** Deterministic cheapest tool satisfying each gated tier. */
    private static final Map<ToolTier, String> SIMPLEST_TOOL = Map.of(
            ToolTier.WOOD, "minecraft:wooden_pickaxe",
            ToolTier.STONE, "minecraft:stone_pickaxe",
            ToolTier.IRON, "minecraft:iron_pickaxe",
            ToolTier.DIAMOND, "minecraft:diamond_pickaxe"
    );

    private static final Map<String, Acquisition> ACQUISITIONS = Map.ofEntries(
            // Stones and earth
            Map.entry("minecraft:cobblestone", new Acquisition("minecraft:stone", ToolTier.WOOD)),
            Map.entry("minecraft:cobbled_deepslate", new Acquisition("minecraft:deepslate", ToolTier.WOOD)),
            Map.entry("minecraft:dirt", new Acquisition("minecraft:dirt", ToolTier.HAND)),
            Map.entry("minecraft:grass_block", new Acquisition("minecraft:grass_block", ToolTier.HAND)),
            Map.entry("minecraft:sand", new Acquisition("minecraft:sand", ToolTier.HAND)),
            Map.entry("minecraft:red_sand", new Acquisition("minecraft:red_sand", ToolTier.HAND)),
            Map.entry("minecraft:gravel", new Acquisition("minecraft:gravel", ToolTier.HAND)),
            Map.entry("minecraft:clay_ball", new Acquisition("minecraft:clay", ToolTier.HAND)),
            Map.entry("minecraft:mud", new Acquisition("minecraft:mud", ToolTier.HAND)),
            Map.entry("minecraft:moss_block", new Acquisition("minecraft:moss_block", ToolTier.HAND)),
            Map.entry("minecraft:snowball", new Acquisition("minecraft:snow_block", ToolTier.HAND)),

            // Stone families (require any pickaxe to drop)
            Map.entry("minecraft:granite", new Acquisition("minecraft:granite", ToolTier.WOOD)),
            Map.entry("minecraft:diorite", new Acquisition("minecraft:diorite", ToolTier.WOOD)),
            Map.entry("minecraft:andesite", new Acquisition("minecraft:andesite", ToolTier.WOOD)),
            Map.entry("minecraft:tuff", new Acquisition("minecraft:tuff", ToolTier.WOOD)),
            Map.entry("minecraft:calcite", new Acquisition("minecraft:calcite", ToolTier.WOOD)),
            Map.entry("minecraft:dripstone_block", new Acquisition("minecraft:dripstone_block", ToolTier.WOOD)),
            Map.entry("minecraft:sandstone", new Acquisition("minecraft:sandstone", ToolTier.WOOD)),
            Map.entry("minecraft:basalt", new Acquisition("minecraft:basalt", ToolTier.WOOD)),
            Map.entry("minecraft:blackstone", new Acquisition("minecraft:blackstone", ToolTier.WOOD)),
            Map.entry("minecraft:end_stone", new Acquisition("minecraft:end_stone", ToolTier.WOOD)),
            Map.entry("minecraft:magma_block", new Acquisition("minecraft:magma_block", ToolTier.WOOD)),
            Map.entry("minecraft:netherrack", new Acquisition("minecraft:netherrack", ToolTier.WOOD)),
            Map.entry("minecraft:soul_sand", new Acquisition("minecraft:soul_sand", ToolTier.HAND)),
            Map.entry("minecraft:glowstone_dust", new Acquisition("minecraft:glowstone", ToolTier.HAND)),

            // Overworld ores (drop the resource, not the block)
            Map.entry("minecraft:coal", new Acquisition("minecraft:coal_ore", ToolTier.WOOD)),
            Map.entry("minecraft:raw_copper", new Acquisition("minecraft:copper_ore", ToolTier.STONE)),
            Map.entry("minecraft:raw_iron", new Acquisition("minecraft:iron_ore", ToolTier.STONE)),
            Map.entry("minecraft:lapis_lazuli", new Acquisition("minecraft:lapis_ore", ToolTier.STONE)),
            Map.entry("minecraft:redstone", new Acquisition("minecraft:redstone_ore", ToolTier.IRON)),
            Map.entry("minecraft:diamond", new Acquisition("minecraft:diamond_ore", ToolTier.IRON)),
            Map.entry("minecraft:emerald", new Acquisition("minecraft:emerald_ore", ToolTier.IRON)),
            Map.entry("minecraft:raw_gold", new Acquisition("minecraft:gold_ore", ToolTier.IRON)),

            // Deepslate ore variants share drops with their stone counterparts;
            // they are covered by the entries above via the stone-tier sources.

            // Nether
            Map.entry("minecraft:quartz", new Acquisition("minecraft:nether_quartz_ore", ToolTier.WOOD)),
            Map.entry("minecraft:gold_nugget", new Acquisition("minecraft:nether_gold_ore", ToolTier.WOOD)),
            Map.entry("minecraft:ancient_debris", new Acquisition("minecraft:ancient_debris", ToolTier.DIAMOND)),

            // Obsidians
            Map.entry("minecraft:obsidian", new Acquisition("minecraft:obsidian", ToolTier.DIAMOND)),
            Map.entry("minecraft:crying_obsidian", new Acquisition("minecraft:crying_obsidian", ToolTier.DIAMOND)),

            // Wood family
            Map.entry("minecraft:oak_log", new Acquisition("minecraft:oak_log", ToolTier.HAND)),
            Map.entry("minecraft:spruce_log", new Acquisition("minecraft:spruce_log", ToolTier.HAND)),
            Map.entry("minecraft:birch_log", new Acquisition("minecraft:birch_log", ToolTier.HAND)),
            Map.entry("minecraft:jungle_log", new Acquisition("minecraft:jungle_log", ToolTier.HAND)),
            Map.entry("minecraft:acacia_log", new Acquisition("minecraft:acacia_log", ToolTier.HAND)),
            Map.entry("minecraft:dark_oak_log", new Acquisition("minecraft:dark_oak_log", ToolTier.HAND)),
            Map.entry("minecraft:mangrove_log", new Acquisition("minecraft:mangrove_log", ToolTier.HAND)),
            Map.entry("minecraft:cherry_log", new Acquisition("minecraft:cherry_log", ToolTier.HAND)),
            Map.entry("minecraft:pale_oak_log", new Acquisition("minecraft:pale_oak_log", ToolTier.HAND)),
            Map.entry("minecraft:bamboo", new Acquisition("minecraft:bamboo", ToolTier.HAND)),

            // Gatherable plants
            Map.entry("minecraft:sweet_berries", new Acquisition("minecraft:sweet_berry_bush", ToolTier.HAND)),
            Map.entry("minecraft:cactus", new Acquisition("minecraft:cactus", ToolTier.HAND)),
            Map.entry("minecraft:sugar_cane", new Acquisition("minecraft:sugar_cane", ToolTier.HAND)),
            Map.entry("minecraft:kelp", new Acquisition("minecraft:kelp_plant", ToolTier.HAND)),
            Map.entry("minecraft:brown_mushroom", new Acquisition("minecraft:brown_mushroom", ToolTier.HAND)),
            Map.entry("minecraft:red_mushroom", new Acquisition("minecraft:red_mushroom", ToolTier.HAND)),
            Map.entry("minecraft:pumpkin", new Acquisition("minecraft:pumpkin", ToolTier.HAND))
    );

    private DirectAcquisitions() {
    }

    /**
     * The cheapest tool from {@code availableItemIds} that satisfies this
     * item's gate — the one the agent should hold while mining. Empty
     * for hand-gatherables or when nothing qualifies (planner will then
     * fold tool acquisition into the plan separately).
     */
    public static Optional<String> qualifyingToolFor(String itemId,
                                                     Set<String> availableItemIds) {
        Acquisition acquisition = ACQUISITIONS.get(itemId);
        if (acquisition == null || acquisition.tool() == ToolTier.HAND) {
            return Optional.empty();
        }
        for (ToolTier tier : ToolTier.values()) {
            if (tier.ordinal() < acquisition.tool().ordinal()) {
                continue;
            }
            Set<String> atOrAbove = PICKAXES_AT_OR_ABOVE.get(tier);
            Optional<String> match = availableItemIds.stream()
                    .filter(atOrAbove::contains)
                    .sorted()
                    .findFirst();
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    /**
     * Items whose source blocks only generate in specific biomes; the
     * planner chains an exploration step before mining them.
     */
    private static final Map<String, String> BIOME_GATES = Map.of(
            "minecraft:cactus", "minecraft:desert",
            "minecraft:bamboo", "minecraft:jungle",
            "minecraft:sweet_berries", "minecraft:taiga",
            "minecraft:red_sand", "minecraft:badlands",
            "minecraft:snowball", "minecraft:snowy_plains"
    );

    /** The biome this item's source block generates in, if restricted. */
    public static Optional<String> requiredBiomeFor(String itemId) {
        return Optional.ofNullable(BIOME_GATES.get(itemId));
    }

    /** Exposed for JVM smoke checks. */
    public static Map<String, String> all() {
        return ACQUISITIONS.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().sourceBlock()));
    }

    public static Optional<String> sourceBlockFor(String itemId) {
        Acquisition acquisition = ACQUISITIONS.get(itemId);
        return acquisition == null ? Optional.empty() : Optional.of(acquisition.sourceBlock());
    }

    /**
     * Returns a refusal message when the given inventory cannot possibly
     * obtain drops from this item's source block, or null when fine.
     */
    public static String missingToolReason(String itemId, Set<String> ownedItemIds) {
        Acquisition acquisition = ACQUISITIONS.get(itemId);
        if (acquisition == null || acquisition.tool() == ToolTier.HAND) {
            return null;
        }
        Set<String> acceptable = PICKAXES_AT_OR_ABOVE.get(acquisition.tool());
        boolean satisfied = ownedItemIds.stream().anyMatch(acceptable::contains);
        return satisfied ? null
                : "requires " + acquisition.tool().name().toLowerCase() + " pickaxe or better";
    }

    /**
     * The cheapest craftable tool that would satisfy this item's tool
     * gate, so the planner can fold tool acquisition into the plan
     * instead of refusing. Empty for hand-gatherables.
     */
    public static Optional<String> simplestToolFor(String itemId) {
        Acquisition acquisition = ACQUISITIONS.get(itemId);
        if (acquisition == null || acquisition.tool() == ToolTier.HAND) {
            return Optional.empty();
        }
        return Optional.ofNullable(SIMPLEST_TOOL.get(acquisition.tool()));
    }
}
