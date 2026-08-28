package com.bhautik.mcagent.action;

import java.util.Optional;

/**
 * Framework-free hunting seam: locate a passive animal and strike it.
 * Mirrors {@link MeleeAttacker} (which handles hostiles that come to
 * the agent) but adds location, because prey must be sought out.
 */
public interface Hunter {

    Hunter NONE = new Hunter() {
        @Override public Optional<MobSite> nearest(String mobId, int radius) {
            return Optional.empty();
        }

        @Override public boolean strike(String mobId, double reach) {
            return false;
        }

        @Override public int countNearby(String mobId, int radius) {
            return 0;
        }
    };

    /** Nearest living animal of this kind within {@code radius} blocks. */
    Optional<MobSite> nearest(String mobId, int radius);

    /**
     * Living animals of this kind within {@code radius}. Hunting reads
     * this to avoid wiping a herd: breeding stock left alive today is
     * the difference between a farm and a longer walk tomorrow.
     */
    int countNearby(String mobId, int radius);

    /**
     * Strikes the nearest animal of this kind if it is within reach,
     * respecting the vanilla attack cooldown.
     *
     * @return true when a blow actually landed
     */
    boolean strike(String mobId, double reach);

    /** Framework-free mob position. */
    record MobSite(int x, int y, int z) {
    }
}
