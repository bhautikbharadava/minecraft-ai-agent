package com.bhautik.mcagent.world;

import java.util.Optional;

/**
 * World seam answering two questions about crafting tables: is one in
 * interaction range right now, and where is the nearest one within a
 * search radius (so plans can walk to it instead of building another).
 */
public interface TableLocator {

    TableLocator NONE = new TableLocator() {
        @Override
        public boolean isNearby() {
            return false;
        }

        @Override
        public Optional<TableSite> nearestWithin(int radius) {
            return Optional.empty();
        }
    };

    boolean isNearby();

    /** Nearest table within {@code radius} blocks, if any. */
    Optional<TableSite> nearestWithin(int radius);

    /** Framework-free block position. */
    record TableSite(int x, int y, int z) {
    }
}
