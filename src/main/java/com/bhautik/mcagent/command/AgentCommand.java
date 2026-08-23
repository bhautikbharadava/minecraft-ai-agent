package com.bhautik.mcagent.command;

import com.bhautik.mcagent.executor.AgentExecutor;
import com.bhautik.mcagent.state.InventoryState;
import com.bhautik.mcagent.state.WorldState;
import com.bhautik.mcagent.state.WorldStateCollector;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.literal;

public final class AgentCommand {
    private AgentCommand() {
    }

    public static void register(AgentExecutor executor) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher, executor));
    }

    private static void register(CommandDispatcher<ServerCommandSource> dispatcher, AgentExecutor executor) {
        dispatcher.register(literal("agent")
                .then(literal("status")
                        .executes(context -> sendStatus(context.getSource(), executor))));
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
