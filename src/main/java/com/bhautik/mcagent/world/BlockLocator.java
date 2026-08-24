package com.bhautik.mcagent.world;

import java.util.Optional;

/**
 * World seam answering two questions about a specific utility block
 * (crafting table, furnace, ...): is one in interaction range right now,
 * and where is the nearest one within a search radius (so plans can walk
 * to it instead of building another).
 */
public interface BlockLocator {

    BlockLocator NONE = new BlockLocator() {
        @Override
        public boolean isNearby() {
            return false;
        }

        @Override
        public Optional<BlockSite> nearestWithin(int radius) {
            return Optional.empty();
        }
    };

    boolean isNearby();

    /** Nearest matching block within {@code radius} blocks, if any. */
    Optional<BlockSite> nearestWithin(int radius);

    /**
     * A standable position NEXT TO the given block — walking into the
     * block itself would make navigation break it as an obstacle.
     * Empty when the implementation offers no opinion (callers then
     * fall back to the block position).
     */
    default Optional<BlockSite> approachFor(BlockSite site) {
        return Optional.empty();
    }

    /** Framework-free block position. */
    record BlockSite(int x, int y, int z) {
    }
}
