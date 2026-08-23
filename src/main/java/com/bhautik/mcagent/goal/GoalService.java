package com.bhautik.mcagent.goal;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.state.InventoryState;
import dev.minecraftai.agent.goal.AgentGoalManager;
import dev.minecraftai.agent.goal.GetItemGoal;
import dev.minecraftai.agent.goal.GoalStatus;
import dev.minecraftai.agent.item.ItemRegistry;
import dev.minecraftai.agent.item.MinecraftItem;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Adapts the framework-free agent goal lifecycle to the live game:
 * resolves items, snapshots real player inventory into the goal
 * model, and drives the active-goal lifecycle.
 */
public final class GoalService {
    private final ItemRegistry itemRegistry = ItemRegistry.vanillaDefaults();
    private final AgentGoalManager goalManager = new AgentGoalManager();

    public boolean isValidItem(String rawName) {
        return itemRegistry.resolve(rawName).isPresent();
    }

    public String getItem(ServerPlayerEntity player, String rawItemName, int requestedCount) {
        MinecraftItem item = itemRegistry.resolve(rawItemName).orElseThrow();
        GetItemGoal goal = new GetItemGoal(item, requestedCount, snapshot(player));
        McAgent.LOGGER.info("[Agent] Goal created: {}", goal.title());
        goalManager.register(goal);
        if (goal.status() == GoalStatus.SUCCESS) {
            McAgent.LOGGER.info("[Agent] Goal already satisfied: {}", goal.title());
        } else {
            McAgent.LOGGER.info("[Agent] Goal started: {}", goal.title());
        }
        return goal.progressReport();
    }

    public String describeActiveGoal() {
        return goalManager.describeActiveGoal();
    }

    public String cancelActiveGoal() {
        boolean hadActiveGoal = goalManager.activeGoal().isPresent();
        String report = goalManager.cancelActiveGoal();
        if (hadActiveGoal) {
            McAgent.LOGGER.info("[Agent] Goal cancelled: {}", report.replace("\n", " "));
        }
        return report;
    }

    private static dev.minecraftai.agent.world.InventoryState snapshot(ServerPlayerEntity player) {
        dev.minecraftai.agent.world.InventoryState snapshot = new dev.minecraftai.agent.world.InventoryState();
        InventoryState.collect(player).itemCounts()
                .forEach((itemId, count) -> snapshot.setCount(new MinecraftItem(itemId), count));
        return snapshot;
    }
}
