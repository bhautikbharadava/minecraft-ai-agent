package com.bhautik.mcagent.world;

/**
 * Framework-free seam for the agent's experience. Enchanting spends XP
 * levels, so every readiness check and (later) XP farm loop reads the
 * live level through this seam. Implemented against the live player by
 * the integration layer; faked in JVM smoke checks.
 */
public interface XpSensor {

    /** Vanilla caps a single enchant offer at this level cost. */
    int MAX_ENCHANT_COST = 30;

    XpSensor NONE = new XpSensor() {
        @Override public int level() {
            return 0;
        }

        @Override public int totalPoints() {
            return 0;
        }
    };

    /** Current experience level (the number shown above the hotbar). */
    int level();

    /** Total accumulated experience points. */
    int totalPoints();

    static XpSensor atLevel(int level) {
        return new XpSensor() {
            @Override public int level() {
                return level;
            }

            @Override public int totalPoints() {
                return pointsForLevel(level);
            }
        };
    }

    /**
     * Total points needed to reach a level, using the vanilla piecewise
     * curve. Lets the XP farm report "N points to go" honestly instead
     * of guessing at level deltas.
     */
    static int pointsForLevel(int level) {
        if (level <= 0) {
            return 0;
        }
        if (level <= 16) {
            return level * level + 6 * level;
        }
        if (level <= 31) {
            return (int) (2.5 * level * level - 40.5 * level + 360.0);
        }
        return (int) (4.5 * level * level - 162.5 * level + 2220.0);
    }
}
