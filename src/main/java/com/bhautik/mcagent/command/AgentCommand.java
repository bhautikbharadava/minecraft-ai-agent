package com.bhautik.mcagent.command;

import com.bhautik.mcagent.executor.AgentExecutor;
import com.bhautik.mcagent.goal.GoalService;
import com.bhautik.mcagent.state.InventoryState;
import com.bhautik.mcagent.state.WorldState;
import com.bhautik.mcagent.state.WorldStateCollector;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class AgentCommand {
    private AgentCommand() {
    }

    public static void register(AgentExecutor executor, GoalService goalService) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                register(dispatcher, executor, goalService));
    }

    private static void register(CommandDispatcher<ServerCommandSource> dispatcher, AgentExecutor executor, GoalService goalService) {
        dispatcher.register(literal("agent")
                .executes(context -> sendUsage(context.getSource()))
                .then(literal("status")
                        .executes(context -> sendStatus(context.getSource(), executor)))
                .then(literal("get")
                        .then(argument("item", StringArgumentType.string())
                                .then(argument("count", IntegerArgumentType.integer(1))
                                        .executes(context -> getItem(
                                                context.getSource(),
                                                goalService,
                                                StringArgumentType.getString(context, "item"),
                                                IntegerArgumentType.getInteger(context, "count"))))))
                .then(literal("goal")
                        .executes(context -> {
                            context.getSource().sendFeedback(() -> Text.literal(goalService.describeActiveGoal()), false);
                            return 1;
                        }))
                .then(literal("cancel")
                        .executes(context -> {
                            context.getSource().sendFeedback(() -> Text.literal(goalService.cancelActiveGoal()), false);
                            return 1;
                        })));
    }

    private static int sendUsage(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("Usage: /agent status | /agent get <item> <count> | /agent goal | /agent cancel"), false);
        return 1;
    }

    private static int getItem(ServerCommandSource source, GoalService goalService, String itemName, int count)
            throws CommandSyntaxException {
        if (!goalService.isValidItem(itemName)) {
            source.sendError(Text.literal("Invalid item name: " + itemName));
            return 0;
        }
        ServerPlayerEntity player = source.getPlayerOrThrow();
        String report = goalService.getItem(player, itemName, count);
        source.sendFeedback(() -> Text.literal(report), false);
        return 1;
    }

    private static int sendStatus(ServerCommandSource source, AgentExecutor executor) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        WorldState worldState = WorldStateCollector.collect(player);
        InventoryState inventoryState = InventoryState.collect(player);

        source.sendFeedback(() -> Text.literal("Agent state: " + executor.state()), false);
        source.sendFeedback(() -> Text.literal(String.format("Health: %.1f / %.1f", worldState.health(), worldState.maxHealth())), false);
        source.sendFeedback(() -> Text.literal("Hunger: " + worldState.hunger()), false);
        source.sendFeedback(() -> Text.literal(String.format("Position: %.1f %.1f %.1f", worldState.x(), worldState.y(), worldState.z())), false);
        source.sendFeedback(() -> Text.literal("Dimension: " + worldState.dimension()), false);
        source.sendFeedback(() -> Text.literal("Inventory: " + inventoryState.summary()), false);
        return 1;
    }
}
