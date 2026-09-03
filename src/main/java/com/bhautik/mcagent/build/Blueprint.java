package com.bhautik.mcagent.build;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A structure the agent can build: block ids at offsets from an origin.
 *
 * <p>Framework-free on purpose. Loading comes from vanilla {@code .nbt}
 * structure files (authorable in-game with structure blocks) but nothing
 * here knows that, so blueprints can be built by hand or from any other
 * format later.
 */
public record Blueprint(String name, int width, int height, int length,
                        List<Placement> placements) {

    /** One block of the structure, offset from the build origin. */
    public record Placement(int dx, int dy, int dz, String blockId) {
    }

    /** Blocks that are placed by clearing, never gathered or set. */
    public static final String AIR = "minecraft:air";

    /**
     * How much of each block the structure needs, ignoring air. This is
     * the shopping list the planner gathers before building starts.
     */
    public Map<String, Integer> materials() {
        Map<String, Integer> needed = new LinkedHashMap<>();
        for (Placement placement : placements) {
            if (AIR.equals(placement.blockId())) {
                continue;
            }
            needed.merge(placement.blockId(), 1, Integer::sum);
        }
        return needed;
    }

    /**
     * True when the design tills ground. Such a structure needs real
     * soil beneath it, not merely solid ground: a hoe converts dirt and
     * grass and nothing else, so siting one on stone or sand leaves most
     * of the field unplaceable.
     */
    public boolean needsSoil() {
        return placements.stream()
                .anyMatch(p -> "minecraft:farmland".equals(p.blockId()));
    }

    /** Total blocks actually placed (air excluded). */
    public int blockCount() {
        return (int) placements.stream()
                .filter(p -> !AIR.equals(p.blockId()))
                .count();
    }

    public String describe() {
        return name + " (" + width + "x" + height + "x" + length + ", "
                + blockCount() + " blocks)";
    }
}
