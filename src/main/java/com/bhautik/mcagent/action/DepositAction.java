package com.bhautik.mcagent.action;

import com.bhautik.mcagent.McAgent;

import java.util.List;

/**
 * Transfers carried items into an adjacent container (the base chest).
 * Verifies by accumulation: success requires at least one stack moved,
 * and completion only when nothing more fits or everything targeted is
 * stored.
 */
public final class DepositAction implements AgentAction {
    /** Moves up to {@code maxStacks} stacks matching {@code itemIds}. */
    public interface Depositor {
        int deposit(List<String> itemIds, int maxStacks);
    }

    private final String title;
    private final List<String> itemIds;
    private final Depositor depositor;

    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private int storedStacks;
    private int idleTicks;

    public DepositAction(String title, List<String> itemIds, Depositor depositor) {
        this.itemIds = List.copyOf(itemIds);
        this.depositor = depositor;
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
        int moved = depositor.deposit(itemIds, 9);
        if (moved > 0) {
            storedStacks += moved;
            idleTicks = 0;
            return;
        }
        // Nothing more to move: either done or nothing matched.
        status = ActionStatus.SUCCESS;
        McAgent.LOGGER.info("[Action] Verified success: {} ({} stacks stored)",
                title, storedStacks);
    }

    @Override
    public void cancel() {
        if (!status.terminal()) {
            status = ActionStatus.CANCELLED;
        }
    }

    public int storedStacks() {
        return storedStacks;
    }
}
