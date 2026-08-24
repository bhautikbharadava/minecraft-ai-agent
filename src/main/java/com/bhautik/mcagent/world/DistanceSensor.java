package com.bhautik.mcagent.world;

/**
 * World seam for live distances, letting actions verify movement against
 * reality (PRD 13) without touching Minecraft classes.
 */
@FunctionalInterface
public interface DistanceSensor {
    double distanceSquaredTo(int x, int y, int z);
}
