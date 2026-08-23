package com.bhautik.mcagent.action;

/**
 * A single executable step planned for a goal. Actions own their
 * lifecycle; execution is driven by ticks from the action runner.
 */
public interface AgentAction {
    String title();

    ActionStatus status();

    /** Begins execution. Called once by the runner. */
    void start();

    /** Advances the action by one server tick until it reaches a terminal status. */
    void tick();

    /** Requests safe cancellation; must leave the world in a consistent state. */
    void cancel();

    /** Human-readable reason when status() is FAILED, otherwise null. */
    String failureReason();
}
