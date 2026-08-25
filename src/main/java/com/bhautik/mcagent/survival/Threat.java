package com.bhautik.mcagent.survival;

/**
 * Outcome of a survival assessment: whether the agent must suspend goal
 * work right now, why, and which recovery behavior fits.
 */
public record Threat(boolean emergency, String reason, boolean needsAir) {

    public static final Threat NONE = new Threat(false, "", false);

    /** Food/health emergency: recover by eating and waiting. */
    public static Threat emergency(String reason) {
        return new Threat(true, reason, false);
    }

    /** Drowning emergency: recover by surfacing, not eating. */
    public static Threat airEmergency(String reason) {
        return new Threat(true, reason, true);
    }
}
