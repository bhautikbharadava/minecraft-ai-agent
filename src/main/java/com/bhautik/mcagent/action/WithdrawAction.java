package com.bhautik.mcagent.action;

import com.bhautik.mcagent.McAgent;

import java.util.List;

/**
 * Pulls up to {@code maxStacks} stacks of the listed items out of a
 * container into the agent's inventory (restock from base). Verified
 * by moved-stack counts; completes when nothing more moves.
 */
public final class WithdrawAction implements AgentAction {
    /** Ticks without any transfer before the action gives up. */
    public static final int IDLE_TIMEOUT_TICKS = 60;

    /** Moves up to {@code maxStacks} matching stacks; returns how many. */
    public interface Withdrawer {
        int withdraw(List<String> itemIds, int maxStacks);
    }

    private final String title;
    private final List<String> itemIds;
    private final Withdrawer withdrawer;

    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private int withdrawnStacks;
    private int idleTicks;

    public WithdrawAction(String title, List<String> itemIds, Withdrawer withdrawer) {
        this.itemIds = List.copyOf(itemIds);
        this.withdrawer = withdrawer;
        this.title = title;
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
        int moved = withdrawer.withdraw(itemIds, 4);
        if (moved > 0) {
            withdrawnStacks += moved;
            idleTicks = 0;
            return;
        }
        status = ActionStatus.SUCCESS;
        McAgent.LOGGER.info("[Action] Verified success: {} ({} stacks taken)",
                title, withdrawnStacks);
    }

    @Override
    public void cancel() {
        if (!status.terminal()) {
            status = ActionStatus.CANCELLED;
        }
    }

    public int withdrawnStacks() {
        return withdrawnStacks;
    }
}
