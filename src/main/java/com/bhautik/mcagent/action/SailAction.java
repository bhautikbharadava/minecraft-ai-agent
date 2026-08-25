package com.bhautik.mcagent.action;

import com.bhautik.mcagent.McAgent;

import java.util.function.DoubleSupplier;

/**
 * Sails the agent across water toward a waypoint: spawns and mounts a
 * carried boat, steers it client-side every tick (boat physics are
 * rider-authoritative), and dismounts on arrival. Arrival is verified
 * against live distance; losing the boat or stalling fails honestly.
 */
public final class SailAction implements AgentAction {
    /** Ticks of no progress before the action fails. */
    public static final int IDLE_TIMEOUT_TICKS = 30 * 20;
    public static final int MAX_ISSUE_ATTEMPTS = 2;

    /** Vehicle control seam; real impl mixes server spawn + client steer. */
    public interface Sailor {
        /** Spawns a carried boat under the agent and mounts it. */
        boolean launch();

        /** Steers toward the waypoint; called while mounted. */
        void steer(int targetX, int targetZ);

        /** True while the agent rides a live boat. */
        boolean mounted();

        void dismount();
    }

    private final String title;
    private final int targetX;
    private final int targetZ;
    private final DoubleSupplier liveDistance;
    private final double arriveDistance;
    private final Sailor sailor;

    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private boolean launched;
    private int issuesUsed;
    private double lastDistance;
    private int idleTicks;

    public SailAction(int targetX, int targetZ, double arriveDistance,
                      DoubleSupplier liveDistance, Sailor sailor) {
        this.targetX = targetX;
        this.targetZ = targetZ;
        this.arriveDistance = arriveDistance;
        this.liveDistance = liveDistance;
        this.sailor = sailor;
        this.title = "Sail to " + targetX + " " + targetZ;
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
        if (!launched) {
            issue();
            return;
        }
        double distance = liveDistance.getAsDouble();
        if (distance <= arriveDistance) {
            sailor.dismount();
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Verified success: {} (distance {})",
                    title, Math.sqrt(distance));
            return;
        }
        if (!sailor.mounted()) {
            fail("lost the boat mid-crossing");
            return;
        }
        sailor.steer(targetX, targetZ);
        if (distance < lastDistance) {
            lastDistance = distance;
            idleTicks = 0;
            return;
        }
        idleTicks++;
        if (idleTicks > IDLE_TIMEOUT_TICKS) {
            sailor.dismount();
            fail("sailing made no progress for " + (IDLE_TIMEOUT_TICKS / 20) + "s");
        }
    }

    @Override
    public void pause() {
        // Nothing external to stop: dismounting mid-water strands the agent.
    }

    @Override
    public void cancel() {
        if (!status.terminal()) {
            if (launched && sailor.mounted()) {
                sailor.dismount();
            }
            status = ActionStatus.CANCELLED;
        }
    }

    private void issue() {
        issuesUsed++;
        lastDistance = liveDistance.getAsDouble();
        McAgent.LOGGER.info("[Action] Started: {}", title);
        launched = sailor.launch();
        if (!launched && issuesUsed >= MAX_ISSUE_ATTEMPTS) {
            fail("could not launch a boat (in water? carrying one?)");
        }
    }

    private void fail(String reason) {
        failureReason = reason;
        status = ActionStatus.FAILED;
        McAgent.LOGGER.warn("[Recovery] Action failed: {} ({})", title, reason);
    }
}
