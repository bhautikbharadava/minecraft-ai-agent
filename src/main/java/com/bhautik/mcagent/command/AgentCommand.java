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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class AgentCommand {
    private AgentCommand() {
    }

    public static void register(AgentExecutor executor, GoalService goalService) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                register(dispatcher, executor, goalService));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher, AgentExecutor executor, GoalService goalService) {
        dispatcher.register(Commands.literal("agent")
                .executes(context -> sendUsage(context.getSource()))
                .then(Commands.literal("status")
                        .executes(context -> sendStatus(context.getSource(), executor)))
                .then(Commands.literal("get")
                        .then(Commands.argument("item", StringArgumentType.string())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .executes(context -> getItem(
                                                context.getSource(),
                                                goalService,
                                                StringArgumentType.getString(context, "item"),
                                                IntegerArgumentType.getInteger(context, "count"))))))
                .then(Commands.literal("goal")
                        .executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(goalService.describeActiveGoal()), false);
                            return 1;
                        }))
                .then(Commands.literal("cancel")
                        .executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(goalService.cancelActiveGoal()), false);
                            return 1;
                        })));
    }

    private static int sendUsage(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Usage: /agent status | /agent get <item> <count> | /agent goal | /agent cancel"), false);
        return 1;
    }

    private static int getItem(CommandSourceStack source, GoalService goalService, String itemName, int count)
            throws CommandSyntaxException {
        if (!goalService.isValidItem(itemName)) {
            source.sendFailure(Component.literal("Invalid item name: " + itemName));
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        String report = goalService.getItem(player, itemName, count);
        source.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int sendStatus(CommandSourceStack source, AgentExecutor executor) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        WorldState worldState = WorldStateCollector.collect(player);
        InventoryState inventoryState = InventoryState.collect(player);

        source.sendSuccess(() -> Component.literal("Agent state: " + executor.state()), false);
        source.sendSuccess(() -> Component.literal(String.format("Health: %.1f / %.1f", worldState.health(), worldState.maxHealth())), false);
        source.sendSuccess(() -> Component.literal("Hunger: " + worldState.hunger()), false);
        source.sendSuccess(() -> Component.literal(String.format("Position: %.1f %.1f %.1f", worldState.x(), worldState.y(), worldState.z())), false);
        source.sendSuccess(() -> Component.literal("Dimension: " + worldState.dimension()), false);
        source.sendSuccess(() -> Component.literal("Inventory: " + inventoryState.summary()), false);
        return 1;
    }
}
