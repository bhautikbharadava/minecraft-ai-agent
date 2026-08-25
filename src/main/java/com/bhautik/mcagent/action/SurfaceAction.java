package com.bhautik.mcagent.action;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.survival.SurvivalMonitor;
import com.bhautik.mcagent.survival.Threat;

/**
 * Drowning recovery: swims the agent upward (server-pushed velocity,
 * synced to the client like knockback) until the survival monitor
 * reports breathable air. Fails honestly if it cannot surface in time.
 */
public final class SurfaceAction implements AgentAction {
    /** Ticks allowed to reach air before giving up. */
    public static final int TIMEOUT_TICKS = 15 * 20;
    /** Push cadence — every 10 ticks keeps upward momentum alive. */
    public static final int PUSH_INTERVAL_TICKS = 10;

    /** Applies one upward swim impulse; true when pushed. */
    public interface Swimmer {
        boolean swimUp();
    }

    private final String title;
    private final SurvivalMonitor monitor;
    private final Swimmer swimmer;

    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private int ticks;
    private int sincePush;

    public SurfaceAction(SurvivalMonitor monitor, Swimmer swimmer) {
        this.monitor = monitor;
        this.swimmer = swimmer;
        this.title = "Surface for air";
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
        status = ActionStatus.RUNNING;
        McAgent.LOGGER.info("[Action] Started: {}", title);
    }

    @Override
    public void tick() {
        if (status != ActionStatus.RUNNING) {
            return;
        }
        Threat threat = monitor.assess();
        if (!threat.needsAir()) {
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Verified success: {} (breathable)", title);
            return;
        }
        sincePush++;
        if (sincePush >= PUSH_INTERVAL_TICKS) {
            sincePush = 0;
            swimmer.swimUp();
        }
        ticks++;
        if (ticks > TIMEOUT_TICKS) {
            fail("could not reach air within " + (TIMEOUT_TICKS / 20) + "s");
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
