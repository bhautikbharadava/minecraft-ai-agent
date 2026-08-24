package com.bhautik.mcagent.survival;

/**
 * Outcome of a survival assessment: whether the agent must suspend goal
 * work right now, and why.
 */
public record Threat(boolean emergency, String reason) {

    public static final Threat NONE = new Threat(false, "");

    public static Threat emergency(String reason) {
        return new Threat(true, reason);
    }
}
