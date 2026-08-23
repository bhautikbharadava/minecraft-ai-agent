package dev.minecraftai.agent;

import com.bhautik.mcagent.action.ActionStatus;
import com.bhautik.mcagent.action.AgentAction;
import com.bhautik.mcagent.action.CraftAction;
import com.bhautik.mcagent.action.MineAction;
import com.bhautik.mcagent.integration.BaritoneIntegration;
import com.bhautik.mcagent.item.DirectAcquisitions;
import dev.minecraftai.agent.command.AgentCommandHandler;
import dev.minecraftai.agent.goal.AgentGoalManager;
import dev.minecraftai.agent.item.ItemRegistry;
import dev.minecraftai.agent.world.InventoryState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.bhautik.mcagent.crafting.RecipeResolver.CraftableRecipe;
import static com.bhautik.mcagent.crafting.RecipeResolver.Grid;
import static com.bhautik.mcagent.crafting.RecipeResolver.SlotSpec;

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
            validateAcquisitionTable();
            validateDependencyPlanning();
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

    private static void validateAcquisitionTable() {
        assertContains(DirectAcquisitions.sourceBlockFor("minecraft:diamond").orElseThrow(),
                "minecraft:diamond_ore");
        assertContains(DirectAcquisitions.sourceBlockFor("minecraft:cobblestone").orElseThrow(),
                "minecraft:stone");
        if (DirectAcquisitions.sourceBlockFor("minecraft:iron_ingot").isPresent()) {
            throw new IllegalStateException("smelted items must not be directly acquirable");
        }
        String diamondGate = DirectAcquisitions.missingToolReason("minecraft:diamond", Set.of());
        assertContains(String.valueOf(diamondGate), "requires iron pickaxe");
        if (DirectAcquisitions.missingToolReason("minecraft:diamond",
                Set.of("minecraft:iron_pickaxe")) != null) {
            throw new IllegalStateException("iron pickaxe should satisfy diamond mining");
        }
        if (DirectAcquisitions.missingToolReason("minecraft:dirt", Set.of()) != null) {
            throw new IllegalStateException("hand-gatherables must never be tool-gated");
        }
        long entries = DirectAcquisitions.all().size();
        if (entries < 50) {
            throw new IllegalStateException("acquisition table suspiciously small: " + entries);
        }
    }

    /**
     * Deterministic checks for M5 dependency planning: post-order
     * chaining, recipe multipliers, unsupported leaves, and cycle guards
     * — all against fake resolvers, no Minecraft required.
     */
    private static void validateDependencyPlanning() {
        com.bhautik.mcagent.planner.Planner planner = new com.bhautik.mcagent.planner.Planner(
                new FakeBackend());
        Map<String, CraftableRecipe> recipes = new HashMap<>();
        recipes.put("minecraft:oak_planks", recipe("minecraft:oak_planks", 4,
                cell("minecraft:oak_log")));
        recipes.put("minecraft:stick", recipe("minecraft:stick", 4,
                cell("minecraft:oak_planks"), cell("minecraft:oak_planks")));
        recipes.put("minecraft:crafting_table", recipe("minecraft:crafting_table", 1,
                cell("minecraft:oak_planks"), cell("minecraft:oak_planks"),
                cell("minecraft:oak_planks"), cell("minecraft:oak_planks")));
        com.bhautik.mcagent.crafting.RecipeResolver resolver = new com.bhautik.mcagent.crafting.RecipeResolver() {
            @Override public Grid grid() { return Grid.INVENTORY_2X2; }
            @Override public Optional<CraftableRecipe> findRecipe(String id) {
                return Optional.ofNullable(recipes.get(id));
            }
        };
        Map<String, Integer> owned = new HashMap<>();
        owned.put("minecraft:oak_log", 1);
        CraftAction.Crafter crafter = (recipe, times) -> times;

        // crafting_table: have 1 log -> mine nothing, craft planks x1, table x1
        List<AgentAction> plan = planner.planAcquisition(resolver,
                id -> owned.getOrDefault(id, 0), Set.of(), id -> 0, crafter,
                "minecraft:crafting_table", 1);
        assertEquals(plan.size(), 2, "table chain length");
        if (!(plan.get(0) instanceof CraftAction)) {
            throw new IllegalStateException("expected planks craft first, got " + plan.get(0).title());
        }
        if (!(plan.get(1) instanceof CraftAction)) {
            throw new IllegalStateException("expected table craft second");
        }

        // sticks x8: need 8 planks -> own 0 logs -> mine 1 log, craft 4 planks, craft 2x sticks
        List<AgentAction> stickPlan = planner.planAcquisition(resolver,
                id -> 0, Set.of(), id -> 0, crafter, "minecraft:stick", 8);
        assertEquals(stickPlan.size(), 3, "stick chain length");
        assertContains(stickPlan.get(0).title(), "Mine");
        assertContains(stickPlan.get(1).title(), "oak_planks");

        try {
            planner.planAcquisition(resolver, id -> 0, Set.of(), id -> 0, crafter,
                    "minecraft:iron_ingot", 1);
            throw new IllegalStateException("smelted goods must fail planning");
        } catch (com.bhautik.mcagent.planner.Planner.PlanningException expected) {
            assertContains(expected.getMessage(), "no supported acquisition strategy");
        }
    }

    private static SlotSpec cell(String itemId) {
        return new SlotSpec(List.of(itemId));
    }

    private static CraftableRecipe recipe(String output, int resultCount, SlotSpec... slots) {
        return new CraftableRecipe(output, resultCount, slots.length, 1, List.of(slots));
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
