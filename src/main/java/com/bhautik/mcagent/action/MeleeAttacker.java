package com.bhautik.mcagent.action;

/**
 * Melee combat seam: strikes the nearest hostile within range, facing
 * it and holding the best available weapon. Best-effort - returns
 * false when nothing is in range or the strike could not happen.
 */
@FunctionalInterface
public interface MeleeAttacker {
    /** @return true when a hostile was engaged (attacked or faced). */
    boolean strikeNearestHostile(double range);
}
