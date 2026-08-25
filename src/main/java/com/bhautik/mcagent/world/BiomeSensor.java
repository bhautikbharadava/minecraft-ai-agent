package com.bhautik.mcagent.world;

/**
 * Framework-free seam reporting the biome the agent currently stands
 * in ("minecraft:desert"). Implemented against the live level; faked
 * in JVM smoke checks.
 */
@FunctionalInterface
public interface BiomeSensor {
    BiomeSensor UNKNOWN = () -> "";

    String current();
}
