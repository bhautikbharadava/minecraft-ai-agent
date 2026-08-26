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
                .then(Commands.literal("explore")
                        .then(Commands.argument("biome", StringArgumentType.word())
                                .executes(context -> explore(
                                        context.getSource(),
                                        goalService,
                                        StringArgumentType.getString(context, "biome")))))
                .then(Commands.literal("base")
                        .executes(context -> runBase(context.getSource(), goalService))
                        .then(Commands.literal("here")
                                .executes(context -> runBase(context.getSource(), goalService))))
                .then(Commands.literal("stash")
                        .then(Commands.argument("item", StringArgumentType.word())
                                .executes(context -> stash(context.getSource(), goalService,
                                        StringArgumentType.getString(context, "item"), "all")
                                        )
                                .then(Commands.argument("amount", StringArgumentType.word())
                                        .executes(context -> stash(context.getSource(), goalService,
                                                StringArgumentType.getString(context, "item"),
                                                StringArgumentType.getString(context, "amount"))))))
                .then(Commands.literal("restock")
                        .then(Commands.argument("item", StringArgumentType.word())
                                .executes(context -> restock(context.getSource(), goalService,
                                        StringArgumentType.getString(context, "item"), 16))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .executes(context -> restock(context.getSource(), goalService,
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
        source.sendSuccess(() -> Component.literal("Usage: /agent status | /agent get <item> <count> | /agent explore <biome|structure> | /agent goal | /agent cancel"), false);
        return 1;
    }

    private static int explore(CommandSourceStack source, GoalService goalService, String name)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String report;
        if (goalService.isValidBiome(player.level(), name)) {
            report = goalService.explore(player, name);
        } else if (com.bhautik.mcagent.world.StructureDirectory.isSearchable(name)) {
            report = goalService.exploreStructure(player, name);
        } else {
            source.sendFailure(Component.literal(
                    "Unknown biome or structure: " + name + " (try desert, jungle, village, "
                            + "mineshaft, stronghold, shipwreck, ruined_portal, buried_treasure)"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int getItem(CommandSourceStack source, GoalService goalService, String itemName, int count)
            throws CommandSyntaxException {
        if (!goalService.isValidItem(itemName) && !com.bhautik.mcagent.item.Kits.isKit(itemName)) {
            source.sendFailure(Component.literal("Invalid item name: " + itemName
                    + " (kits: " + com.bhautik.mcagent.item.Kits.names() + ")"));
            return 0;
        }
        try {
            ServerPlayer player = source.getPlayerOrException();
            String report = goalService.getItem(player, itemName, count);
            source.sendSuccess(() -> Component.literal(report), false);
            return 1;
        } catch (Exception failure) {
            com.bhautik.mcagent.McAgent.LOGGER.error("[Agent] /agent get failed",
                    failure);
            source.sendFailure(Component.literal("Get failed: " + failure));
            return 0;
        }
    }

    private static int runBase(CommandSourceStack source, GoalService goalService)
            throws CommandSyntaxException {
        try {
            ServerPlayer player = source.getPlayerOrException();
            String report = goalService.base(player);
            source.sendSuccess(() -> Component.literal(report), false);
            return 1;
        } catch (Exception failure) {
            com.bhautik.mcagent.McAgent.LOGGER.error("[Agent] /agent base failed",
                    failure);
            source.sendFailure(Component.literal("Base setup failed: "
                    + failure));
            return 0;
        }
    }

    private static int stash(CommandSourceStack source, GoalService goalService,
                             String item, String amount) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String report = goalService.stash(player, item, amount);
        if (report.startsWith("Invalid") || report.startsWith("No base")) {
            source.sendFailure(Component.literal(report));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int restock(CommandSourceStack source, GoalService goalService,
                               String name, int count) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String report = goalService.restock(player, name, count);
        if (report.startsWith("Invalid") || report.startsWith("No base")
                || report.startsWith("No food")) {
            source.sendFailure(Component.literal(report));
            return 0;
        }
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
