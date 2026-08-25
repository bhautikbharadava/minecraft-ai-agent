package com.bhautik.mcagent.action;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.integration.BaritoneIntegration;

import java.util.function.Supplier;

/**
 * Explores through the navigation backend until the agent stands in the
 * target biome. Arrival is verified independently against the live
 * biome sensor; the backend is never trusted on its own.
 */
public final class ExploreAction implements AgentAction {
    /** Ticks of wandering without finding the biome before re-issuing. */
    public static final int IDLE_TIMEOUT_TICKS = 60 * 20;
    public static final int MAX_ISSUE_ATTEMPTS = 5;

    private final String title;
    private final String targetBiomeId;
    private final int centerX;
    private final int centerZ;
    private final Supplier<String> biomeAt;
    private final BaritoneIntegration backend;

    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private int issuesUsed;
    private int idleTicks;

    public ExploreAction(String targetBiomeId, int centerX, int centerZ,
                         Supplier<String> biomeAt, BaritoneIntegration backend) {
        this.targetBiomeId = targetBiomeId;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.biomeAt = biomeAt;
        this.backend = backend;
        this.title = "Explore to " + targetBiomeId.replaceFirst("^minecraft:", "");
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
        if (arrived()) {
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Already in target biome: {}", title);
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
        if (arrived()) {
            backend.stop();
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Verified success: {} (biome {})", title, biomeAt.get());
            return;
        }
        idleTicks++;
        if (idleTicks < IDLE_TIMEOUT_TICKS) {
            return;
        }
        if (issuesUsed < MAX_ISSUE_ATTEMPTS) {
            McAgent.LOGGER.info("[Recovery] No biome match yet on [{}], re-issuing exploration", title);
            idleTicks = 0;
            issue();
            return;
        }
        fail("target biome not reached after "
                + (IDLE_TIMEOUT_TICKS / 20) + "s x" + MAX_ISSUE_ATTEMPTS);
    }

    @Override
    public void pause() {
        backend.stop();
    }

    @Override
    public void cancel() {
        if (!status.terminal()) {
            backend.stop();
            status = ActionStatus.CANCELLED;
        }
    }

    private boolean arrived() {
        return targetBiomeId.equals(biomeAt.get());
    }

    private void issue() {
        issuesUsed++;
        idleTicks = 0;
        McAgent.LOGGER.info("[Action] Started: {}", title);
        if (!backend.startExplore(centerX, centerZ)) {
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
