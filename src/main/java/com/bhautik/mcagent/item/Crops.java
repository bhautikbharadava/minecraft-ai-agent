package com.bhautik.mcagent.item;

import java.util.Map;
import java.util.Optional;

/**
 * Items the agent grows rather than mines or hunts.
 *
 * <p>Wheat is the reason this exists: cows breed with it, breeding is
 * what makes leather renewable, and wild wheat only generates in
 * village farms. Growing it closes the loop
 * (seeds -> crop -> wheat -> calves -> leather) with no village needed.
 */
public final class Crops {

    /** A growable item: the block it grows as, and the seed that starts it. */
    public record Crop(String cropBlockId, String seedItemId) {
    }

    private static final Map<String, Crop> CROPS = Map.of(
            "minecraft:wheat", new Crop("minecraft:wheat", "minecraft:wheat_seeds"),
            "minecraft:carrot", new Crop("minecraft:carrots", "minecraft:carrot"),
            "minecraft:potato", new Crop("minecraft:potatoes", "minecraft:potato"),
            "minecraft:beetroot", new Crop("minecraft:beetroots",
                    "minecraft:beetroot_seeds")
    );

    private Crops() {
    }

    public static Optional<Crop> forItem(String itemId) {
        return Optional.ofNullable(CROPS.get(itemId));
    }

    public static boolean isGrowable(String itemId) {
        return CROPS.containsKey(itemId);
    }
}
