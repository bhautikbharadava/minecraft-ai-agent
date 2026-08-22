package com.bhautik.mcagent.executor;

import com.bhautik.mcagent.integration.BaritoneIntegration;
import com.bhautik.mcagent.planner.Planner;

public final class AgentExecutor {
    private final Planner planner;
    private final BaritoneIntegration baritoneIntegration;
    private AgentState state = AgentState.IDLE;

    public AgentExecutor(Planner planner, BaritoneIntegration baritoneIntegration) {
        this.planner = planner;
        this.baritoneIntegration = baritoneIntegration;
    }

    public AgentState state() {
        return state;
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
