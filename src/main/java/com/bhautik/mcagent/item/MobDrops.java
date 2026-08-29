package com.bhautik.mcagent.item;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Items obtained by hunting passive animals rather than by mining.
 *
 * <p>Leather is the reason this exists: the enchanting-table chain
 * (table -> book -> leather) and every bookshelf need it, and no block
 * drops it. {@link DirectAcquisitions} maps items to source *blocks*;
 * this maps items to source *mobs*, and the planner tries it after
 * mining and before smelting/crafting.
 */
public final class MobDrops {

    /**
     * A huntable source. {@code dropsPerKill} is deliberately the
     * conservative end of the vanilla range so plans over-hunt slightly
     * rather than finishing short (a cow drops 0-2 leather).
     */
    public record Hunt(String mobId, int dropsPerKill) {
    }

    /** What each species accepts for breeding (vanilla love-mode food). */
    private static final Map<String, String> BREEDING_FOOD = Map.of(
            "minecraft:cow", "minecraft:wheat",
            "minecraft:sheep", "minecraft:wheat",
            "minecraft:pig", "minecraft:carrot",
            "minecraft:chicken", "minecraft:wheat_seeds"
    );

    /** The item that breeds this animal, if the agent knows one. */
    public static Optional<String> breedingFoodFor(String mobId) {
        return Optional.ofNullable(BREEDING_FOOD.get(mobId));
    }

    private static final Map<String, Hunt> DROPS = Map.ofEntries(
            Map.entry("minecraft:leather", new Hunt("minecraft:cow", 1)),
            Map.entry("minecraft:beef", new Hunt("minecraft:cow", 1)),
            Map.entry("minecraft:porkchop", new Hunt("minecraft:pig", 1)),
            Map.entry("minecraft:mutton", new Hunt("minecraft:sheep", 1)),
            Map.entry("minecraft:chicken", new Hunt("minecraft:chicken", 1)),
            Map.entry("minecraft:feather", new Hunt("minecraft:chicken", 1))
    );

    private MobDrops() {
    }

    public static Optional<Hunt> huntFor(String itemId) {
        return Optional.ofNullable(DROPS.get(itemId));
    }

    public static boolean isHuntable(String itemId) {
        return DROPS.containsKey(itemId);
    }

    /** Every item the agent knows how to hunt for. */
    public static Set<String> huntableItems() {
        return DROPS.keySet();
    }
}
