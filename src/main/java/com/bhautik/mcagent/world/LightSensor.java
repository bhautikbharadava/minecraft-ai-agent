package com.bhautik.mcagent.world;

/**
 * Framework-free seam for the light level where the agent stands
 * (0-15). Used to decide when torch placement is worthwhile.
 */
@FunctionalInterface
public interface LightSensor {
    LightSensor BRIGHT = () -> 15;

    int level();
}
