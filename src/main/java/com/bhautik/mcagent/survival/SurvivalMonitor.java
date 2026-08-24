package com.bhautik.mcagent.survival;

/**
 * Framework-free seam answering one question each assessment: is the
 * agent in a survival emergency (PRD 15: survival outranks the active
 * goal)? Implemented against live player state by the integration
 * layer; faked in JVM smoke checks.
 */
@FunctionalInterface
public interface SurvivalMonitor {

    /** Health at or below this (half-hearts*... actually points) suspends work. */
    float CRITICAL_HEALTH = 8.0f;
    /** Hunger at or below this suspends work. */
    int CRITICAL_HUNGER = 6;

    SurvivalMonitor NONE = () -> Threat.NONE;

    Threat assess();
}
