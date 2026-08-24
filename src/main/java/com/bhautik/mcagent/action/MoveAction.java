package com.bhautik.mcagent.action;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.integration.BaritoneIntegration;

import java.util.function.DoubleSupplier;

/**
 * Walks the agent to a target position through the navigation backend.
 * Progress is verified independently against live distance; the action
 * never trusts the backend's own completion signal.
 */
public final class MoveAction implements AgentAction {
    /** Ticks without progress before the backend is re-issued or fails. */
    public static final int IDLE_TIMEOUT_TICKS = 600;
    public static final int MAX_ISSUE_ATTEMPTS = 2;

    private final String title;
    private final int targetX;
    private final int targetY;
    private final int targetZ;
    private final DoubleSupplier liveDistance;
    private final double arriveDistance;
    private final BaritoneIntegration backend;

    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private int issuesUsed;
    private double lastDistance;
    private int idleTicks;

    public MoveAction(int x, int y, int z, double arriveDistance,
                      DoubleSupplier liveDistance, BaritoneIntegration backend) {
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        this.arriveDistance = arriveDistance;
        this.liveDistance = liveDistance;
        this.backend = backend;
        this.title = "Go to crafting_table";
        this.lastDistance = Double.MAX_VALUE;
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
        if (liveDistance.getAsDouble() <= arriveDistance) {
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Already at target for: {}", title);
            return;
        }
        status = ActionStatus.RUNNING;
        issue();
    }

    @Override
    public void tick() {
        if (status != ActionStatus.RUNNING) {
            return;
        }
        double distance = liveDistance.getAsDouble();
        if (distance <= arriveDistance) {
            backend.stop();
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Verified success: {} (distance {})",
                    title, Math.sqrt(distance));
            return;
        }
        if (distance < lastDistance) {
            lastDistance = distance;
            idleTicks = 0;
            return;
        }
        idleTicks++;
        if (idleTicks < IDLE_TIMEOUT_TICKS) {
            return;
        }
        if (issuesUsed < MAX_ISSUE_ATTEMPTS) {
            McAgent.LOGGER.info("[Recovery] No progress on [{}], re-issuing navigation", title);
            idleTicks = 0;
            issue();
            return;
        }
        fail("navigation made no progress for " + (IDLE_TIMEOUT_TICKS / 20) + "s");
    }

    @Override
    public void cancel() {
        if (!status.terminal()) {
            backend.stop();
            status = ActionStatus.CANCELLED;
        }
    }

    private void issue() {
        issuesUsed++;
        lastDistance = liveDistance.getAsDouble();
        if (lastDistance <= arriveDistance) {
            status = ActionStatus.SUCCESS;
            return;
        }
        McAgent.LOGGER.info("[Action] Started: {}", title);
        if (!backend.startGoTo(targetX, targetY, targetZ)) {
            fail("no navigation backend available: " + backend.describe());
        }
    }

    private void fail(String reason) {
        backend.stop();
        failureReason = reason;
        status = ActionStatus.FAILED;
        McAgent.LOGGER.warn("[Recovery] Action failed: {} ({})", title, reason);
    }
}
