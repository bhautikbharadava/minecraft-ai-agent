package com.bhautik.mcagent.action;

import com.bhautik.mcagent.world.BlockLocator;

import java.util.Optional;

/**
 * Framework-free seam for bucket work. Separate from
 * {@link PlaceBlockAction.Placer} because a filled bucket is used ON a
 * block rather than placed as one, so it never goes through the
 * BlockItem path.
 *
 * <p>Exists so the agent can MAKE obsidian by pouring water on lava —
 * how players actually get it — instead of walking the world hoping to
 * find the little that generates naturally.
 */
public interface FluidHandler {

    FluidHandler NONE = new FluidHandler() {
        @Override public Optional<BlockLocator.BlockSite> nearest(String fluidId, int radius) {
            return Optional.empty();
        }

        @Override public boolean fillFrom(BlockLocator.BlockSite waterSite) {
            return false;
        }

        @Override public boolean pourOnto(BlockLocator.BlockSite lavaSite) {
            return false;
        }

        @Override public boolean carriesWater() {
            return false;
        }
    };

    /**
     * Nearest source block of this fluid ("minecraft:water" /
     * "minecraft:lava"). Only true sources count — flowing fluid does
     * not turn to obsidian.
     */
    Optional<BlockLocator.BlockSite> nearest(String fluidId, int radius);

    /** Fills a carried empty bucket from the given water source. */
    boolean fillFrom(BlockLocator.BlockSite waterSite);

    /**
     * Empties a carried water bucket onto a lava source, turning it to
     * obsidian. Verification is the caller's job — check the block.
     */
    boolean pourOnto(BlockLocator.BlockSite lavaSite);

    /** True when a filled water bucket is carried. */
    boolean carriesWater();
}
