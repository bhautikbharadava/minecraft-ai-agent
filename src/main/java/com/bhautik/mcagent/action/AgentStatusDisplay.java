package com.bhautik.mcagent.action;

/**
 * Framework-free seam for showing the agent's state in game.
 *
 * <p>Exists because the log is a poor window onto a live agent: it is
 * enormous, Baritone floods it, and the failure that matters is often a
 * silent repetition rather than an error. Putting the current goal,
 * step and reasoning on screen makes what the agent is doing legible
 * while it happens.
 */
public interface AgentStatusDisplay {

    AgentStatusDisplay NONE = new AgentStatusDisplay() {
        @Override public void showGoal(String goal, String step, int done, int total) {
        }

        @Override public void note(String reasoning) {
        }

        @Override public void finish(String outcome, boolean success) {
        }
    };

    /**
     * The persistent line: which goal is running, which step it is on,
     * and how far through the plan it is.
     */
    void showGoal(String goal, String step, int done, int total);

    /**
     * A transient one-liner about what the agent is thinking right now -
     * why it is detouring, what it is waiting for, what it just decided.
     */
    void note(String reasoning);

    /** The goal ended; report the outcome and take the display down. */
    void finish(String outcome, boolean success);
}
