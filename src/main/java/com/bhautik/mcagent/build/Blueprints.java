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
        return Set.of("wheat_farm", "enchanting_room");
    }

    public static Optional<Blueprint> byName(String rawName) {
        if (rawName == null) {
            return Optional.empty();
        }
        return switch (rawName.trim().toLowerCase()) {
            case "wheat_farm", "farm" -> Optional.of(wheatFarm());
            case "enchanting_room", "bookshelf_ring", "enchanting" ->
                    Optional.of(enchantingRoom());
            default -> Optional.empty();
        };
    }

    /**
     * A full-power enchanting setup: table in the middle, fifteen
     * bookshelves around it, and the gap between them kept clear.
     *
     * <p>The geometry is vanilla's, not decoration. Shelves only count
     * from RADIUS 2, and only when the ring at radius 1 is empty at both
     * the table's level and the one above - a shelf pushed up against
     * the table contributes nothing. Getting this wrong produces a room
     * that looks right and enchants at level 3.
     *
     * <p>Fifteen is deliberate: vanilla caps enchanting power at 15, so
     * ringing the perimeter twice over would cost 32 shelves (96 books,
     * 96 leather) for no extra levels. One perimeter slot is left open
     * as a doorway, which is exactly 15.
     */
    public static Blueprint enchantingRoom() {
        List<Blueprint.Placement> placements = new ArrayList<>();
        int reach = 2;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                boolean core = Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
                boolean centre = dx == 0 && dz == 0;
                boolean doorway = dx == reach && dz == 0;
                if (centre) {
                    placements.add(new Blueprint.Placement(dx, 0, dz,
                            "minecraft:enchanting_table"));
                } else if (core || doorway) {
                    // The radius-1 ring MUST be empty for the shelves to
                    // register; the doorway is how the agent walks in.
                    placements.add(new Blueprint.Placement(dx, 0, dz, Blueprint.AIR));
                } else {
                    placements.add(new Blueprint.Placement(dx, 0, dz,
                            "minecraft:bookshelf"));
                }
                // Headroom, and the second empty level the power check
                // requires over the inner ring.
                placements.add(new Blueprint.Placement(dx, 1, dz, Blueprint.AIR));
            }
        }
        int side = reach * 2 + 1;
        return new Blueprint("enchanting_room", side, 3, side, placements);
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
