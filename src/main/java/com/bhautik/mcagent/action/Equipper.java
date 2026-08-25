package com.bhautik.mcagent.action;

/**
 * Tool-selection seam: moves the named item into the agent's hand.
 * Implemented server-side so both the server (drop calculation) and
 * the client (rendering, auto-tool) agree on what is held.
 */
@FunctionalInterface
public interface Equipper {
    /**
     * Attempts to hold {@code itemId}; true when it is in hand after the
     * call (including "already held"). Best-effort.
     */
    boolean equip(String itemId);
}
