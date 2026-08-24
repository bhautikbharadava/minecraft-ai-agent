package com.bhautik.mcagent.action;

import com.bhautik.mcagent.integration.BaritoneIntegration;
import com.bhautik.mcagent.McAgent;

import java.util.function.IntSupplier;

/**
 * Mines until the live inventory count of the target item reaches the
 * requested total. Progress is measured independently from the backend:
 * the action only trusts the inventory count supplier.
 */
public final class MineAction implements AgentAction {
    /** Ticks without inventory progress before the backend is re-issued or the action fails. */
    public static final int IDLE_TIMEOUT_TICKS = 600;
    public static final int MAX_ISSUE_ATTEMPTS = 2;

    private final String title;
    private final String sourceBlockName;
    private final int targetTotal;
    private final IntSupplier liveCount;
    private final BaritoneIntegration backend;
    private final String preferredToolItemId;

    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private int issuesUsed;
    private boolean equipped;
    private int lastCount;
    private int idleTicks;

    public MineAction(String sourceBlockName, int baselineCount, int targetTotal,
                      IntSupplier liveCount, BaritoneIntegration backend) {
        this(sourceBlockName, baselineCount, targetTotal, liveCount, backend, null);
    }

    public MineAction(String sourceBlockName, int baselineCount, int targetTotal,
                      IntSupplier liveCount, BaritoneIntegration backend,
                      String preferredToolItemId) {
        this.sourceBlockName = sourceBlockName;
        this.targetTotal = targetTotal;
        this.liveCount = liveCount;
        this.backend = backend;
        this.preferredToolItemId = preferredToolItemId;
        this.title = "Mine " + (targetTotal - baselineCount) + " " + sourceBlockName.replaceFirst("^minecraft:", "");
        this.lastCount = baselineCount;
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
        issue();
    }

    @Override
    public void tick() {
        if (status != ActionStatus.RUNNING) {
            return;
        }
        int count = Math.max(liveCount.getAsInt(), 0);
        if (count >= targetTotal) {
            backend.stop();
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Verified success: {} (inventory {})", title, count);
            return;
        }
        if (count > lastCount) {
            lastCount = count;
            idleTicks = 0;
            return;
        }
        idleTicks++;
        if (idleTicks < IDLE_TIMEOUT_TICKS) {
            return;
        }
        if (issuesUsed < MAX_ISSUE_ATTEMPTS) {
            McAgent.LOGGER.info("[Recovery] No progress on [{}], re-issuing mining request", title);
            idleTicks = 0;
            issue();
            return;
        }
        fail("mining made no progress for " + (IDLE_TIMEOUT_TICKS / 20) + "s");
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

    private void issue() {
        issuesUsed++;
        lastCount = Math.max(liveCount.getAsInt(), 0);
        int missing = targetTotal - lastCount;
        if (missing <= 0) {
            status = ActionStatus.SUCCESS;
            return;
        }
        McAgent.LOGGER.info("[Action] Started: {}", title);
        if (preferredToolItemId != null && !equipped) {
            equipped = backend.equip(preferredToolItemId);
            if (!equipped) {
                // Best-effort: mining proceeds, but wrong-tier breaks may
                // drop nothing and the idle timeout will surface it honestly.
                McAgent.LOGGER.warn("[Recovery] Could not equip {} for {}",
                        preferredToolItemId.replaceFirst("^minecraft:", ""), title);
            }
        }
        if (!backend.startMine(sourceBlockName, missing)) {
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
