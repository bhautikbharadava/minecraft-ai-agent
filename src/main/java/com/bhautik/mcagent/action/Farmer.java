package com.bhautik.mcagent.action;

import com.bhautik.mcagent.world.BlockLocator;

import java.util.Optional;

/**
 * Framework-free seam for crop farming: till ground, sow seed, push
 * growth along, and harvest what ripened.
 *
 * <p>This is what closes the leather cycle. Cows breed with wheat, and
 * wild wheat only grows in village farms, so without growing its own
 * the agent's renewable leather supply depends on finding a village.
 */
public interface Farmer {

    Farmer NONE = new Farmer() {
        @Override public Optional<BlockLocator.BlockSite> tillableSpot(int radius) {
            return Optional.empty();
        }

        @Override public int tillPlot(BlockLocator.BlockSite centre, int radius) {
            return 0;
        }

        @Override public int sowAll(String seedItemId, int radius) {
            return 0;
        }

        @Override public int cropsPlanted(String cropBlockId, int radius) {
            return 0;
        }

        @Override public int cropsRipe(String cropBlockId, int radius) {
            return 0;
        }

        @Override public boolean hurryGrowth(String cropBlockId, int radius) {
            return false;
        }

        @Override public int harvestRipe(String cropBlockId, int radius) {
            return 0;
        }
    };

    /**
     * A dirt or grass block beside water that can become farmland.
     * Water matters: dry farmland reverts and crops stall.
     */
    Optional<BlockLocator.BlockSite> tillableSpot(int radius);

    /**
     * Tills every workable soil block around the centre, giving a real
     * field rather than a single square. Requires a carried hoe.
     *
     * @return how many blocks became farmland
     */
    int tillPlot(BlockLocator.BlockSite centre, int radius);

    /**
     * Sows every empty farmland block in range, one seed each, until the
     * seeds run out.
     *
     * @return how many crops were planted
     */
    int sowAll(String seedItemId, int radius);

    /** Crops of this kind planted nearby, ripe or not. */
    int cropsPlanted(String cropBlockId, int radius);

    /** Crops of this kind that have reached full maturity. */
    int cropsRipe(String cropBlockId, int radius);

    /**
     * Applies bone meal to one unripe crop if any is carried. Growth is
     * otherwise left to the world clock, which is slow.
     *
     * @return true when bone meal was actually spent
     */
    boolean hurryGrowth(String cropBlockId, int radius);

    /** Breaks every ripe crop nearby so its drops can be collected. */
    int harvestRipe(String cropBlockId, int radius);
}
