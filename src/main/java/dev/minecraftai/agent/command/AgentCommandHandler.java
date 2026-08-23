package dev.minecraftai.agent.command;

import dev.minecraftai.agent.goal.AgentGoalManager;
import dev.minecraftai.agent.goal.GetItemGoal;
import dev.minecraftai.agent.item.ItemRegistry;
import dev.minecraftai.agent.item.MinecraftItem;
import dev.minecraftai.agent.world.InventoryState;

import java.util.Optional;

public final class AgentCommandHandler {
    private final ItemRegistry itemRegistry;
    private final InventoryState inventoryState;
    private final AgentGoalManager goalManager;

    public AgentCommandHandler(ItemRegistry itemRegistry, InventoryState inventoryState, AgentGoalManager goalManager) {
        this.itemRegistry = itemRegistry;
        this.inventoryState = inventoryState;
        this.goalManager = goalManager;
    }

    public String handle(String command) {
        String[] parts = command.trim().split("\\s+");
        if (parts.length == 0 || !"/agent".equals(parts[0])) {
            return "Unknown command.";
        }
        if (parts.length == 2 && "goal".equals(parts[1])) {
            return goalManager.describeActiveGoal();
        }
        if (parts.length == 2 && "cancel".equals(parts[1])) {
            return goalManager.cancelActiveGoal();
        }
        if (parts.length == 4 && "get".equals(parts[1])) {
            return get(parts[2], parts[3]);
        }
        return "Usage: /agent get <item> <count>, /agent goal, or /agent cancel";
    }

    private String get(String itemName, String countText) {
        Optional<MinecraftItem> item = itemRegistry.resolve(itemName);
        if (item.isEmpty()) {
            return "Invalid item name: " + itemName;
        }
        int count;
        try {
            count = Integer.parseInt(countText);
        } catch (NumberFormatException exception) {
            return "Invalid count: " + countText + " (must be a positive integer)";
        }
        if (count <= 0) {
            return "Invalid count: " + countText + " (must be greater than zero)";
        }
        GetItemGoal goal = new GetItemGoal(item.get(), count, inventoryState);
        return goalManager.register(goal).progressReport();
    }
}
