package dev.minecraftai.agent;

import dev.minecraftai.agent.command.AgentCommandHandler;
import dev.minecraftai.agent.goal.AgentGoalManager;
import dev.minecraftai.agent.item.ItemRegistry;
import dev.minecraftai.agent.world.InventoryState;

public final class AgentCli {
    private AgentCli() {
    }

    public static void main(String[] args) {
        AgentCommandHandler handler = new AgentCommandHandler(
                ItemRegistry.vanillaDefaults(),
                new InventoryState(),
                new AgentGoalManager());
        if (args.length > 0 && "validate".equals(args[0])) {
            validate(handler);
            return;
        }
        System.out.println(handler.handle(String.join(" ", args)));
    }

    private static void validate(AgentCommandHandler handler) {
        assertContains(handler.handle("/agent get cobblestone 64"), "Status: ACTIVE");
        assertContains(handler.handle("/agent goal"), "Missing: 64");
        assertContains(handler.handle("/agent cancel"), "Status: CANCELLED");
        assertContains(handler.handle("/agent get not_an_item 1"), "Invalid item name");
        assertContains(handler.handle("/agent get cobblestone 0"), "Invalid count");
        assertContains(handler.handle("/agent get cobblestone nope"), "Invalid count");
    }

    private static void assertContains(String actual, String expected) {
        if (!actual.contains(expected)) {
            throw new IllegalStateException("Expected [" + actual + "] to contain [" + expected + "]");
        }
    }
}
