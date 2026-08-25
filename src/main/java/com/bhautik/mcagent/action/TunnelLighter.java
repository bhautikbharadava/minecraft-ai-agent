package com.bhautik.mcagent.action;

/**
 * Drops a torch near the agent when the tunnel is dark enough for mobs.
 * Called periodically by {@link MineAction} while digging, so lighting
 * happens continuously during the dig instead of after it.
 */
@FunctionalInterface
public interface TunnelLighter {
    /**
     * Attempts one torch placement; true when a torch was placed.
     * Implementations decide darkness/stock rules and stay best-effort.
     */
    boolean tryPlace();

    /** Light below this level counts as mob-spawnable territory. */
    int MIN_LIGHT = 8;
}
