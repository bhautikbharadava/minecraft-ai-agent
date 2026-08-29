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

    /**
     * Stops external effects (e.g. in-flight navigation) while keeping
     * state, so survival handling can suspend and later re-launch the
     * action. Unlike {@link #cancel()} the action stays non-terminal.
     */
    default void pause() {
    }

    /** Human-readable reason when status() is FAILED, otherwise null. */
    String failureReason();

    /**
     * Cleanup steps (e.g. collecting a placed utility block) are
     * best-effort: their failure must never cost a replan attempt or
     * fail an otherwise satisfied goal.
     */
    default boolean bestEffort() {
        return false;
    }

    /**
     * Whether re-planning could plausibly change this failure. Most
     * failures are worth another attempt (a vein ran out, navigation
     * stalled). Some are settled facts about the world — no prey exists
     * for miles — and re-running the identical plan just burns attempts
     * and repeats side effects. Those return false so the goal fails
     * immediately with the real reason.
     */
    default boolean retryable() {
        return true;
    }
}
