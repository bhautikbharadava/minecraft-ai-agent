package com.bhautik.mcagent.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Blueprints that ship with the agent, so building works before anyone
 * authors a structure file.
 *
 * <p>Generated rather than stored: a farm is a formula (water in the
 * middle, tilled ground within hydration range), and generating it keeps
 * the design readable and adjustable. Structure files loaded from disk
 * cover everything that is not expressible this way.
 */
public final class Blueprints {

    /** Vanilla hydrates farmland up to four blocks from water. */
    private static final int HYDRATION = 4;

    private Blueprints() {
    }

    public static Set<String> names() {
        return Set.of("wheat_farm");
    }

    public static Optional<Blueprint> byName(String rawName) {
        if (rawName == null) {
            return Optional.empty();
        }
        return switch (rawName.trim().toLowerCase()) {
            case "wheat_farm", "farm" -> Optional.of(wheatFarm());
            default -> Optional.empty();
        };
    }

    /**
     * The classic 9x9 plot: one water source at the centre, farmland
     * everywhere else on that layer, and clear air above so crops have
     * room. Every square sits within hydration range of the centre.
     */
    public static Blueprint wheatFarm() {
        List<Blueprint.Placement> placements = new ArrayList<>();
        int side = HYDRATION * 2 + 1;
        for (int dx = -HYDRATION; dx <= HYDRATION; dx++) {
            for (int dz = -HYDRATION; dz <= HYDRATION; dz++) {
                boolean centre = dx == 0 && dz == 0;
                placements.add(new Blueprint.Placement(dx, 0, dz,
                        centre ? "minecraft:water" : "minecraft:farmland"));
                // Crops need the space above kept clear.
                placements.add(new Blueprint.Placement(dx, 1, dz, Blueprint.AIR));
            }
        }
        return new Blueprint("wheat_farm", side, 2, side, placements);
    }
}
