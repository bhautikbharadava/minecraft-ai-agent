package com.bhautik.mcagent.world;

/**
 * Live anchor for open-ended navigation: where the agent stands right
 * now (explore processes wander outward from this point).
 */
public interface PositionAnchor {
    int x();

    int z();
}
