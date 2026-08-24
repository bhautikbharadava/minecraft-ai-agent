package com.bhautik.mcagent.world;

/**
 * Planning/execution seam answering one world question: is a crafting
 * table within interaction range? Implemented against the live level by
 * the integration layer; faked in JVM smoke checks.
 */
@FunctionalInterface
public interface TableLocator {
    TableLocator NONE = () -> false;

    boolean isNearby();
}
