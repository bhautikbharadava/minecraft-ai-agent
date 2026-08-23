package dev.minecraftai.agent.goal;

import dev.minecraftai.agent.item.MinecraftItem;
import dev.minecraftai.agent.world.InventoryState;

public final class GetItemGoal implements AgentGoal {
    private final MinecraftItem targetItem;
    private final int requestedAmount;
    private final InventoryState inventoryState;
    private GoalStatus status = GoalStatus.IDLE;

    public GetItemGoal(MinecraftItem targetItem, int requestedAmount, InventoryState inventoryState) {
        if (requestedAmount <= 0) {
            throw new IllegalArgumentException("Requested amount must be greater than zero.");
        }
        this.targetItem = targetItem;
        this.requestedAmount = requestedAmount;
        this.inventoryState = inventoryState;
    }

    public MinecraftItem targetItem() {
        return targetItem;
    }

    public int requestedAmount() {
        return requestedAmount;
    }

    public int currentAmount() {
        return inventoryState.count(targetItem);
    }

    public int missingAmount() {
        return Math.max(0, requestedAmount - currentAmount());
    }

    @Override
    public String title() {
        return "Get " + requestedAmount + " " + targetItem.displayName();
    }

    @Override
    public GoalStatus status() {
        return status;
    }

    @Override
    public void activate() {
        status = missingAmount() == 0 ? GoalStatus.SUCCESS : GoalStatus.ACTIVE;
    }

    @Override
    public void cancel() {
        if (status == GoalStatus.ACTIVE) {
            status = GoalStatus.CANCELLED;
        }
    }

    @Override
    public String progressReport() {
        return String.join(System.lineSeparator(),
                "Goal: " + title(),
                "Current: " + currentAmount(),
                "Missing: " + missingAmount(),
                "Status: " + status);
    }
}
