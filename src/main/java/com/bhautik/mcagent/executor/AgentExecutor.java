package com.bhautik.mcagent.executor;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.action.AgentAction;
import com.bhautik.mcagent.integration.BaritoneIntegration;
import com.bhautik.mcagent.planner.Planner;

import java.util.Optional;

/**
 * Runs one action at a time, driven by server ticks. The agent brain
 * (GoalService) decides what to launch; this class only executes.
 */
public final class AgentExecutor {
    private final Planner planner;
    private final BaritoneIntegration baritoneIntegration;
    private AgentAction current;

    public AgentExecutor(Planner planner, BaritoneIntegration baritoneIntegration) {
        this.planner = planner;
        this.baritoneIntegration = baritoneIntegration;
    }

    public boolean busy() {
        return current != null;
    }

    /** Launches an action when idle. Returns false if one is already running. */
    public boolean launch(AgentAction action) {
        if (busy()) {
            return false;
        }
        current = action;
        action.start();
        return true;
    }

    public void tick() {
        if (current == null) {
            return;
        }
        current.tick();
    }

    /**
     * Returns and clears the finished action, if any. Null while an action
     * is still running.
     */
    public Optional<AgentAction> pollFinished() {
        if (current != null && current.status().terminal()) {
            AgentAction finished = current;
            current = null;
            return Optional.of(finished);
        }
        return Optional.empty();
    }

    public void cancelCurrent(String reason) {
        if (current == null) {
            return;
        }
        McAgent.LOGGER.info("[Recovery] Cancelling action [{}]: {}", current.title(), reason);
        current.cancel();
        current = null;
    }

    public AgentState state() {
        return busy() ? AgentState.EXECUTING : AgentState.IDLE;
    }

    /** Title of the action currently running, or null when idle. */
    public String currentTitle() {
        return current == null ? null : current.title();
    }

    public Planner planner() {
        return planner;
    }

    public BaritoneIntegration baritoneIntegration() {
        return baritoneIntegration;
    }

    public enum AgentState {
        IDLE,
        PLANNING,
        EXECUTING,
        BLOCKED
    }
}
