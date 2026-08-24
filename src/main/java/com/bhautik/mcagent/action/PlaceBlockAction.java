package com.bhautik.mcagent.action;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.world.BlockLocator;

/**
 * Places a carried block item into the world near the agent. The action
 * only trusts independent verification: the block must be observable via
 * the supplied {@link BlockLocator} before SUCCESS is declared.
 */
public final class PlaceBlockAction implements AgentAction {
    /** Failed placement attempts before the action gives up. */
    public static final int MAX_PLACE_ATTEMPTS = 20;

    /** Performs real world placement; returns whether the block persisted. */
    public interface Placer {
        Result place(String itemId);

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
    private final Placer placer;
    private final BlockLocator verification;

    private final String title;
    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private int attempts;

    public PlaceBlockAction(String itemId, Placer placer, BlockLocator verification) {
        this.itemId = itemId;
        this.placer = placer;
        this.verification = verification;
        this.title = "Place " + itemId.replaceFirst("^minecraft:", "");
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
        // Reality check wins over the executor's own claim (PRD 13).
        if (verification.isNearby()) {
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Verified success: {} (block present in world)", title);
            return;
        }
        if (attempts >= MAX_PLACE_ATTEMPTS) {
            fail("placement never verified in world");
            return;
        }
        attempts++;
        Placer.Result result = placer.place(itemId);
        if (!result.success()) {
            if (attempts >= MAX_PLACE_ATTEMPTS) {
                fail(result.failureReason());
            }
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
