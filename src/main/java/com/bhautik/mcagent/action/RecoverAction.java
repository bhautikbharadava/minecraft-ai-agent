package com.bhautik.mcagent.action;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.survival.SurvivalMonitor;
import com.bhautik.mcagent.survival.Threat;

/**
 * Survival recovery step (PRD 15): eats whatever food the agent carries
 * and waits until the survival monitor reports the emergency over.
 * Fails honestly when the agent stays critical past the timeout — for
 * example when starving with no food in inventory.
 */
public final class RecoverAction implements AgentAction {
    /** Ticks allowed to reach a non-emergency state before giving up. */
    public static final int TIMEOUT_TICKS = 30 * 20;

    /** Eats the best available food; returns nutrition applied (0 if none). */
    public interface Feeder {
        int eatBest();
    }

    private final String title;
    private final SurvivalMonitor monitor;
    private final Feeder feeder;

    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private int ticks;
    private boolean ateSomething;

    public RecoverAction(SurvivalMonitor monitor, Feeder feeder) {
        this.monitor = monitor;
        this.feeder = feeder;
        this.title = "Recover (eat/wait)";
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public ActionStatus status() {
        return status;
    }

    @Override
    public String failureReason() {
        return failureReason;
    }

    @Override
    public void start() {
        if (status != ActionStatus.PENDING) {
            return;
        }
        // Starting while already safe is fine; verification below wins.
        status = ActionStatus.RUNNING;
        McAgent.LOGGER.info("[Action] Started: {}", title);
    }

    @Override
    public void tick() {
        if (status != ActionStatus.RUNNING) {
            return;
        }
        Threat threat = monitor.assess();
        if (!threat.emergency()) {
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Verified success: {}{}",
                    title, ateSomething ? " (ate food)" : "");
            return;
        }
        int nutrition = feeder.eatBest();
        if (nutrition > 0) {
            ateSomething = true;
        }
        ticks++;
        if (ticks > TIMEOUT_TICKS) {
            fail("still critical after " + (TIMEOUT_TICKS / 20) + "s"
                    + (ateSomething ? "" : "; carried no edible food"));
        }
    }

    @Override
    public void cancel() {
        if (!status.terminal()) {
            status = ActionStatus.CANCELLED;
        }
    }

    private void fail(String reason) {
        failureReason = reason;
        status = ActionStatus.FAILED;
        McAgent.LOGGER.warn("[Recovery] Action failed: {} ({})", title, reason);
    }
}
