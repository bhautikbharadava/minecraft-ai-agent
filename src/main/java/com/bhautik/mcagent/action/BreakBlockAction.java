package com.bhautik.mcagent.action;

import com.bhautik.mcagent.McAgent;

import java.util.function.IntSupplier;

/**
 * Breaks a previously placed block so the agent gets its item back.
 * Verified at two layers: the breaker confirms the cleared world state,
 * and this action independently confirms the item reached inventory.
 */
public final class BreakBlockAction implements AgentAction {
    /** Failed attempts before giving up. */
    public static final int MAX_BREAK_ATTEMPTS = 20;
    /** Ticks allowed between a successful break and the item appearing. */
    public static final int IDLE_TIMEOUT_TICKS = 60;

    /** Performs real world breaking; returns whether the block was removed. */
    public interface Breaker {
        Result break_(String itemId);

        record Result(boolean success, String failureReason) {
            public static Result ok() {
                return new Result(true, null);
            }

            public static Result failed(String reason) {
                return new Result(false, reason);
            }
        }
    }

    private final String itemId;
    private final Breaker breaker;
    private final IntSupplier liveItemCount;
    private final int baselineCount;

    private final String title;
    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private int attempts;
    private boolean broken;
    private int idleAfterBreak;

    public BreakBlockAction(String itemId, Breaker breaker, IntSupplier liveItemCount) {
        this.itemId = itemId;
        this.breaker = breaker;
        this.liveItemCount = liveItemCount;
        this.baselineCount = Math.max(liveItemCount.getAsInt(), 0);
        this.title = "Collect " + itemId.replaceFirst("^minecraft:", "");
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
        int count = Math.max(liveItemCount.getAsInt(), 0);
        if (count > baselineCount) {
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Verified success: {} (inventory {})", title, count);
            return;
        }
        if (broken) {
            // World cleared but the item has not registered yet.
            idleAfterBreak++;
            if (idleAfterBreak > IDLE_TIMEOUT_TICKS) {
                fail("broken block never reached inventory");
            }
            return;
        }
        if (attempts >= MAX_BREAK_ATTEMPTS) {
            fail("break never succeeded");
            return;
        }
        attempts++;
        Breaker.Result result = breaker.break_(itemId);
        if (result.success()) {
            broken = true;
            if (Math.max(liveItemCount.getAsInt(), 0) > baselineCount) {
                status = ActionStatus.SUCCESS;
                McAgent.LOGGER.info("[Action] Verified success: {} (inventory {})",
                        title, Math.max(liveItemCount.getAsInt(), 0));
            }
        } else if (attempts >= MAX_BREAK_ATTEMPTS) {
            fail(result.failureReason());
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
