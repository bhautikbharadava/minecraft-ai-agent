package dev.minecraftai.agent;

import com.bhautik.mcagent.action.ActionStatus;
import com.bhautik.mcagent.action.AgentAction;
import com.bhautik.mcagent.action.MineAction;
import com.bhautik.mcagent.integration.BaritoneIntegration;
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
            validateMineActionLifecycle();
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

    /**
     * Deterministic JVM checks for the M3 action lifecycle: satisfied
     * goals, progress-driven success, stall recovery, and cancellation —
     * all against a fake backend, no Minecraft required.
     */
    private static void validateMineActionLifecycle() {
        FakeBackend backend = new FakeBackend();

        MineAction alreadySatisfied = new MineAction("minecraft:stone", 64, 64, () -> 64, backend);
        alreadySatisfied.start();
        assertEquals(alreadySatisfied.status(), ActionStatus.SUCCESS, "satisfied before start");

        int[] count = {10};
        MineAction progressing = new MineAction("minecraft:stone", 10, 20, () -> count[0], backend);
        progressing.start();
        assertEquals(progressing.status(), ActionStatus.RUNNING, "running after start");
        count[0] = 19;
        progressing.tick();
        assertEquals(progressing.status(), ActionStatus.RUNNING, "still short of target");
        count[0] = 20;
        progressing.tick();
        assertEquals(progressing.status(), ActionStatus.SUCCESS, "success at target count");

        MineAction stalled = new MineAction("minecraft:stone", 0, 8, () -> 0, backend);
        stalled.start();
        for (int i = 0; i <= MineAction.IDLE_TIMEOUT_TICKS * MineAction.MAX_ISSUE_ATTEMPTS; i++) {
            stalled.tick();
        }
        assertEquals(stalled.status(), ActionStatus.FAILED, "stall exhausts retries");
        if (stalled.failureReason() == null) {
            throw new IllegalStateException("Failed action must report a reason");
        }

        MineAction cancelled = new MineAction("minecraft:stone", 0, 8, () -> 0, backend);
        cancelled.start();
        cancelled.cancel();
        assertEquals(cancelled.status(), ActionStatus.CANCELLED, "cancel while running");

        BaritoneIntegration unavailable = BaritoneIntegration.unavailable();
        MineAction offline = new MineAction("minecraft:stone", 0, 8, () -> 0, unavailable);
        offline.start();
        assertEquals(offline.status(), ActionStatus.FAILED, "missing backend fails fast");
        assertContains(String.valueOf(offline.failureReason()), "no navigation backend");
    }

    private static void assertEquals(Object actual, Object expected, String label) {
        if (actual != expected) {
            throw new IllegalStateException(label + ": expected [" + expected + "] got [" + actual + "]");
        }
    }

    private static void assertContains(String actual, String expected) {
        if (!actual.contains(expected)) {
            throw new IllegalStateException("Expected [" + actual + "] to contain [" + expected + "]");
        }
    }

    private static final class FakeBackend implements BaritoneIntegration {
        @Override
        public boolean available() {
            return true;
        }

        @Override
        public boolean startMine(String blockName, int quantity) {
            return true;
        }

        @Override
        public void stop() {
        }

        @Override
        public String describe() {
            return "fake test backend";
        }
    }
}
