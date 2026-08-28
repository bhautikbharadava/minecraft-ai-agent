package com.bhautik.mcagent.action;

import com.bhautik.mcagent.McAgent;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Enchants one carried item at a real, nearby enchanting table. Vanilla
 * owns the offer roll, the lapis cost and the XP spend; this action
 * only drives the menu and verifies the result against the live item.
 *
 * <p>Modeled on {@link SmeltAction}: proximity is required to act, the
 * outcome is judged by observable state (the item carries enchantments)
 * rather than by the seam reporting success.
 */
public final class EnchantAction implements AgentAction {

    /** Attempts before giving up on a table that will not take the item. */
    public static final int MAX_ISSUE_ATTEMPTS = 20;

    /** Performs real enchanting-table interaction. */
    public interface Enchanter {
        /**
         * Enchants {@code itemId} at the nearby table, choosing the best
         * offer that costs at least {@code minLevel} and is affordable.
         */
        Result enchant(String itemId, int minLevel);

        record Result(boolean success, String failureReason) {
            public static Result ok() {
                return new Result(true, null);
            }

            public static Result failed(String reason) {
                return new Result(false, reason);
            }
        }
    }

    private final String title;
    private final String itemId;
    private final int minLevel;
    private final Enchanter enchanter;
    private final BooleanSupplier tableInRange;
    private final IntSupplier liveEnchantedCount;

    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private int attempts;
    private int baseline;

    public EnchantAction(String itemId, int minLevel, Enchanter enchanter,
                         BooleanSupplier tableInRange,
                         IntSupplier liveEnchantedCount) {
        this.itemId = itemId;
        this.minLevel = minLevel;
        this.enchanter = enchanter;
        this.tableInRange = tableInRange;
        this.liveEnchantedCount = liveEnchantedCount;
        this.title = "Enchant " + itemId.replaceFirst("^minecraft:", "")
                + (minLevel > 1 ? " (level " + minLevel + "+)" : "");
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
        baseline = Math.max(liveEnchantedCount.getAsInt(), 0);
        if (!tableInRange.getAsBoolean()) {
            fail("enchanting table is not within range");
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
        // Satisfaction first: an already-enchanted copy ends the action.
        if (liveEnchantedCount.getAsInt() > baseline) {
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Verified success: {} (enchanted)", title);
            return;
        }
        if (!tableInRange.getAsBoolean()) {
            fail("enchanting table is not within range");
            return;
        }
        var result = enchanter.enchant(itemId, minLevel);
        attempts++;
        if (result.success()) {
            // Verified on the next tick against live item state, never on
            // the seam's own word.
            return;
        }
        if (attempts >= MAX_ISSUE_ATTEMPTS) {
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
