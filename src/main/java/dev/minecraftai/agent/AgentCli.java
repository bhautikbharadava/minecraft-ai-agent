package dev.minecraftai.agent;

import com.bhautik.mcagent.action.ActionStatus;
import com.bhautik.mcagent.action.AgentAction;
import com.bhautik.mcagent.action.CraftAction;
import com.bhautik.mcagent.action.MineAction;
import com.bhautik.mcagent.action.PlaceBlockAction;
import com.bhautik.mcagent.integration.BaritoneIntegration;
import com.bhautik.mcagent.item.DirectAcquisitions;
import dev.minecraftai.agent.command.AgentCommandHandler;
import dev.minecraftai.agent.goal.AgentGoalManager;
import dev.minecraftai.agent.item.ItemRegistry;
import dev.minecraftai.agent.world.InventoryState;

import java.util.ArrayList;
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
        InventoryState inventory = new InventoryState();
        AgentCommandHandler handler = new AgentCommandHandler(
                ItemRegistry.vanillaDefaults(),
                inventory,
                new AgentGoalManager());
        if (args.length > 0 && "validate".equals(args[0])) {
            validate(handler, inventory);
            validateMineActionLifecycle();
            validateAcquisitionTable();
            validateDependencyPlanning();
            validateSurvivalInterruptions();
            validateExploration();
            validateXpSensing();
            validateHunting();
            return;
        }
        System.out.println(handler.handle(String.join(" ", args)));
    }

    private static void validate(AgentCommandHandler handler, InventoryState inventory) {
        assertContains(handler.handle("/agent get cobblestone 64"), "Status: ACTIVE");
        assertContains(handler.handle("/agent goal"), "Missing: 64");
        assertContains(handler.handle("/agent cancel"), "Status: CANCELLED");
        assertContains(handler.handle("/agent get not_an_item 1"), "Invalid item name");
        assertContains(handler.handle("/agent get cobblestone 0"), "Invalid count");
        assertContains(handler.handle("/agent get cobblestone nope"), "Invalid count");

        // A terminal goal must never block a new one.
        assertContains(handler.handle("/agent get oak_log 1"), "Status: ACTIVE");
        assertContains(handler.handle("/agent cancel"), "CANCELLED");
        assertContains(handler.handle("/agent cancel"), "No active goal to cancel.");
        // Regression guard for the in-game path: goals marked FAILED/SUCCESS
        // mid-run must read as inactive to the manager.
        dev.minecraftai.agent.goal.AgentGoalManager mgr = new dev.minecraftai.agent.goal.AgentGoalManager();
        InventoryState scratch = new InventoryState();
        dev.minecraftai.agent.goal.GetItemGoal terminalCheck =
                new dev.minecraftai.agent.goal.GetItemGoal(
                        new dev.minecraftai.agent.item.MinecraftItem("minecraft:dirt"), 4, scratch);
        mgr.register(terminalCheck);
        assertContains(terminalCheck.progressReport(), "Status: ACTIVE");
        terminalCheck.markFailed("boom");
        if (mgr.activeGoal().isPresent()) {
            throw new IllegalStateException("terminal goal must not count as active");
        }
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

        // Gated mining must equip the right tier before breaking blocks:
        // wrong-tool mining destroys ores without dropping anything.
        FakeBackend equipping = new FakeBackend();
        MineAction gatedMine = new MineAction("minecraft:diamond_ore", 0, 3, () -> 3,
                equipping, "minecraft:iron_pickaxe");
        gatedMine.start();
        assertEquals(gatedMine.status(), ActionStatus.SUCCESS, "satisfied before start still works");
        assertEquals(equipping.lastEquipped, null, "no equip when already satisfied");
        int[] diamonds = {0};
        int[] equipCalls = {0};
        MineAction gatedMineLive = new MineAction("minecraft:diamond_ore", 0, 3,
                () -> diamonds[0], equipping, "minecraft:iron_pickaxe", null,
                itemId -> {
                    equipCalls[0]++;
                    equipping.lastEquipped = itemId;
                    return true;
                });
        gatedMineLive.start();
        assertEquals(equipCalls[0], 1, "equips exactly once before issuing mining");
        assertEquals(equipping.lastEquipped, "minecraft:iron_pickaxe",
                "equips the qualifying pickaxe before issuing mining");
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
     * Deterministic checks for M5/M6 dependency planning: post-order
     * chaining, recipe multipliers, unsupported leaves, cycle guards,
     * and crafting-table insertion/dedup — all against fake resolvers,
     * no Minecraft required.
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
        recipes.put("minecraft:chest", tableRecipe("minecraft:chest", 1,
                cell("minecraft:oak_planks"), cell("minecraft:oak_planks"),
                cell("minecraft:oak_planks"), cell("minecraft:oak_planks"),
                cell("minecraft:oak_planks"), cell("minecraft:oak_planks"),
                cell("minecraft:oak_planks"), cell("minecraft:oak_planks")));
        com.bhautik.mcagent.crafting.RecipeResolver resolver = new com.bhautik.mcagent.crafting.RecipeResolver() {
            @Override public Grid grid() { return Grid.INVENTORY_2X2; }
            @Override public Optional<CraftableRecipe> findRecipe(String id) {
                return Optional.ofNullable(recipes.get(id));
            }
        };
        // Tool-gated mining must fold tool acquisition into the plan:
        // cobblestone with bare hands plans a wooden pickaxe first.
        recipes.put("minecraft:wooden_pickaxe", tableRecipe("minecraft:wooden_pickaxe", 1,
                cell("minecraft:oak_planks"), cell("minecraft:oak_planks"),
                cell("minecraft:oak_planks"), SlotSpec.EMPTY,
                cell("minecraft:stick"), SlotSpec.EMPTY,
                SlotSpec.EMPTY, cell("minecraft:stick"), SlotSpec.EMPTY));
        // Torch upkeep: mining plans top lighting up before digging.
        recipes.put("minecraft:torch", recipe("minecraft:torch", 4,
                cell("minecraft:coal"), cell("minecraft:stick")));
        CraftAction.Crafter crafter = (recipe, times) -> times;
        PlaceBlockAction.Placer placer = itemId -> PlaceBlockAction.Placer.Result.ok();

        // crafting_table: have 1 log -> mine nothing, craft planks x1, table x1
        Map<String, Integer> owned = new HashMap<>();
        owned.put("minecraft:oak_log", 1);
        List<AgentAction> plan = planner.planAcquisition(resolver,
                id -> owned.getOrDefault(id, 0), Set.of(), id -> 0,
                env(crafter, placer, com.bhautik.mcagent.world.BlockLocator.NONE),
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
                torchStocked(), Set.of(), id -> 0, env(crafter, placer, com.bhautik.mcagent.world.BlockLocator.NONE),
                "minecraft:stick", 8);
        assertEquals(stickPlan.size(), 3, "stick chain length");
        assertContains(stickPlan.get(0).title(), "Mine");
        assertContains(stickPlan.get(1).title(), "oak_planks");

        try {
            planner.planAcquisition(resolver, torchStocked(), Set.of(), id -> 0,
                    env(crafter, placer, com.bhautik.mcagent.world.BlockLocator.NONE), "minecraft:iron_ingot", 1);
            throw new IllegalStateException("smelted goods must fail planning");
        } catch (com.bhautik.mcagent.planner.Planner.PlanningException expected) {
            assertContains(expected.getMessage(), "no supported acquisition strategy");
        }

        // A table-gated goal whose ingredients are unresolvable still
        // refuses honestly (iron_ingot needs smelting, a later milestone).
        recipes.put("minecraft:iron_pickaxe", tableRecipe("minecraft:iron_pickaxe", 1,
                cell("minecraft:iron_ingot"), cell("minecraft:iron_ingot"),
                cell("minecraft:iron_ingot"), cell("minecraft:stick"),
                cell("minecraft:stick")));
        try {
            planner.planAcquisition(resolver, torchStocked(), Set.of(), id -> 0,
                    env(crafter, placer, com.bhautik.mcagent.world.BlockLocator.NONE), "minecraft:iron_pickaxe", 1);
            throw new IllegalStateException("unresolvable table-gated goods must fail planning");
        } catch (com.bhautik.mcagent.planner.Planner.PlanningException expected) {
            assertContains(expected.getMessage(), "no supported acquisition strategy");
        }

        // M6: a craftable table-gated item plans acquire-table -> place ->
        // craft -> pick-the-table-back-up when the world has no table yet.
        List<AgentAction> chestPlan = planner.planAcquisition(resolver,
                torchStocked(), Set.of(), id -> 0, env(crafter, placer, com.bhautik.mcagent.world.BlockLocator.NONE),
                "minecraft:chest", 1);
        assertEquals(chestPlan.size(), 6, "chest chain length");
        assertContains(chestPlan.get(0).title(), "Mine");
        assertContains(chestPlan.get(1).title(), "oak_planks");
        assertContains(chestPlan.get(2).title(), "Craft");
        assertEquals(chestPlan.get(3).getClass(), PlaceBlockAction.class, "table placement step");
        assertEquals(((PlaceBlockAction) chestPlan.get(3)).status(), ActionStatus.PENDING,
                "placement starts PENDING");
        assertContains(chestPlan.get(4).title(), "chest");
        assertEquals(chestPlan.get(5).getClass(),
                com.bhautik.mcagent.action.BreakBlockAction.class,
                "placed tables are collected last");

        // With a table already in range, no table acquisition or placement,
        // and the world's table is left alone (no collect step).
        List<AgentAction> nearTablePlan = planner.planAcquisition(resolver,
                torchStocked(), Set.of(), id -> 0, env(crafter, placer, blockNearby()),
                "minecraft:chest", 1);
        assertEquals(nearTablePlan.size(), 3, "near-table chain length");
        if (nearTablePlan.stream().anyMatch(action -> action instanceof PlaceBlockAction)
                || nearTablePlan.stream()
                        .anyMatch(action -> action instanceof com.bhautik.mcagent.action.BreakBlockAction)) {
            throw new IllegalStateException("existing table must not trigger placement or collection");
        }

        // A placed table beyond interaction range is walked to, not rebuilt,
        // and pre-existing world tables are never collected.
        List<AgentAction> farTablePlan = planner.planAcquisition(resolver,
                torchStocked(), Set.of(), id -> 0, env(crafter, placer, blockFarAway()),
                "minecraft:chest", 1);
        assertEquals(farTablePlan.size(), 4, "far-table chain length");
        assertContains(farTablePlan.get(0).title(), "Mine");
        assertContains(farTablePlan.get(1).title(), "oak_planks");
        assertEquals(farTablePlan.get(2).getClass(), com.bhautik.mcagent.action.MoveAction.class,
                "walks to the existing table");
        assertContains(farTablePlan.get(3).title(), "chest");
        if (farTablePlan.stream().anyMatch(action -> action instanceof PlaceBlockAction)
                || farTablePlan.stream()
                        .anyMatch(action -> action instanceof com.bhautik.mcagent.action.BreakBlockAction)) {
            throw new IllegalStateException("reachable world tables must not be re-placed or collected");
        }
        double[] distance = {100.0};
        com.bhautik.mcagent.action.MoveAction walking = new com.bhautik.mcagent.action.MoveAction(
                "crafting_table", 30, 70, -12, 9.0, () -> distance[0], new FakeBackend());
        walking.start();
        assertEquals(walking.status(), ActionStatus.RUNNING, "move starts when far");
        distance[0] = 4.0;
        walking.tick();
        assertEquals(walking.status(), ActionStatus.SUCCESS, "arrival verified by distance");

        // Carrying a table skips crafting one but still places (and later
        // collects) it.
        Map<String, Integer> carryingTable = new HashMap<>();
        carryingTable.put("minecraft:crafting_table", 1);
        carryingTable.put("minecraft:torch", 99);
        carryingTable.put("minecraft:sweet_berries", 99);
        List<AgentAction> carriedPlan = planner.planAcquisition(resolver,
                id -> carryingTable.getOrDefault(id, 0), Set.of(), id -> 0,
                env(crafter, placer, com.bhautik.mcagent.world.BlockLocator.NONE), "minecraft:chest", 1);
        assertEquals(carriedPlan.size(), 5, "carried-table chain length");
        assertEquals(carriedPlan.get(2).getClass(), PlaceBlockAction.class, "still places carried table");
        assertEquals(carriedPlan.get(4).getClass(),
                com.bhautik.mcagent.action.BreakBlockAction.class,
                "carried table comes home too");

        // Multiple table-gated crafts share one placement per plan.
        List<AgentAction> bulkPlan = planner.planAcquisition(resolver,
                torchStocked(), Set.of(), id -> 0, env(crafter, placer, com.bhautik.mcagent.world.BlockLocator.NONE),
                "minecraft:chest", 3);
        long placements = bulkPlan.stream()
                .filter(action -> action instanceof PlaceBlockAction)
                .count();
        assertEquals(placements, 1L, "one placement per plan");

        // Tool-gated mining with no pickaxe plans the pickaxe chain first:
        // mine log -> planks -> sticks -> place table -> wooden_pickaxe -> mine.
        List<AgentAction> cobblePlan = planner.planAcquisition(resolver,
                torchStocked(), Set.of(), id -> 0, env(crafter, placer, com.bhautik.mcagent.world.BlockLocator.NONE),
                "minecraft:cobblestone", 3);
        if (cobblePlan.size() != 8) {
            throw new IllegalStateException("tool-first chain length: expected [8] got ["
                    + cobblePlan.size() + "] " + cobblePlan.stream()
                            .map(AgentAction::title).toList());
        }
        assertContains(cobblePlan.get(0).title(), "Mine");
        assertContains(cobblePlan.get(5).title(), "wooden_pickaxe");
        assertEquals(cobblePlan.get(6).getClass(), MineAction.class,
                "mining comes after the tool is crafted");
        assertEquals(cobblePlan.get(7).getClass(),
                com.bhautik.mcagent.action.BreakBlockAction.class,
                "tool chain collects its table last");
        placements = cobblePlan.stream()
                .filter(action -> action instanceof PlaceBlockAction)
                .count();
        assertEquals(placements, 1L, "tool chain places exactly one table");

        // Without any craftable qualifying tool the refusal stays honest
        // and explains what could not be planned.
        recipes.remove("minecraft:wooden_pickaxe");
        try {
            planner.planAcquisition(resolver, torchStocked(), Set.of(), id -> 0,
                    env(crafter, placer, blockNearby()), "minecraft:cobblestone", 3);
            throw new IllegalStateException("ungateable mining must fail planning");
        } catch (com.bhautik.mcagent.planner.Planner.PlanningException expected) {
            assertContains(expected.getMessage(), "cannot plan a wooden_pickaxe");
            assertContains(expected.getMessage(), "requires wood pickaxe");
        }
        recipes.put("minecraft:wooden_pickaxe", tableRecipe("minecraft:wooden_pickaxe", 1,
                cell("minecraft:oak_planks"), cell("minecraft:oak_planks"),
                cell("minecraft:oak_planks"), SlotSpec.EMPTY,
                cell("minecraft:stick"), SlotSpec.EMPTY,
                SlotSpec.EMPTY, cell("minecraft:stick"), SlotSpec.EMPTY));

        // M7: with a furnace route, smelted goods plan furnace access ->
        // input mining -> fuel mining -> cook -> collect. The agent owns a
        // stone pickaxe here so no tool chain is needed.
        Map<String, com.bhautik.mcagent.crafting.SmeltingResolver.SmeltableRecipe> smelting =
                new HashMap<>();
        smelting.put("minecraft:iron_ingot",
                new com.bhautik.mcagent.crafting.SmeltingResolver.SmeltableRecipe(
                        "minecraft:iron_ingot", List.of(
                        "minecraft:deepslate_iron_ore", "minecraft:raw_iron")));
        Set<String> miner = Set.of("minecraft:stone_pickaxe");
        // The agent carries a furnace so the plan places it without
        // needing the whole furnace-crafting chain in the fake recipes.
        Map<String, Integer> carryingFurnace = new HashMap<>();
        carryingFurnace.put("minecraft:furnace", 1);
        carryingFurnace.put("minecraft:torch", 99);
        carryingFurnace.put("minecraft:sweet_berries", 99);
        List<AgentAction> ingotPlan = planner.planAcquisition(resolver,
                id -> carryingFurnace.getOrDefault(id, 0), miner, id -> 0,
                env(crafter, placer, com.bhautik.mcagent.world.BlockLocator.NONE,
                        output -> Optional.ofNullable(smelting.get(output))),
                "minecraft:iron_ingot", 1);
        if (ingotPlan.size() != 5) {
            throw new IllegalStateException("smelt chain length: expected [5] got ["
                    + ingotPlan.size() + "] " + ingotPlan.stream()
                            .map(AgentAction::title).toList());
        }
        // Gathering happens before placement so Baritone never mines
        // through the freshly placed furnace.
        assertContains(ingotPlan.get(0).title(), "iron_ore");
        assertContains(ingotPlan.get(1).title(), "coal");
        assertEquals(ingotPlan.get(2).getClass(), PlaceBlockAction.class,
                "furnace placed only after mining");
        assertEquals(ingotPlan.get(3).getClass(),
                com.bhautik.mcagent.action.SmeltAction.class, "cook step");
        assertEquals(ingotPlan.get(4).getClass(),
                com.bhautik.mcagent.action.BreakBlockAction.class,
                "furnace collected last");
        assertContains(ingotPlan.get(4).title(), "furnace");

        // The iron route still refuses honestly when the furnace path is
        // missing and only the nugget loop exists.
        try {
            planner.planAcquisition(resolver, torchStocked(), miner, id -> 0,
                    env(crafter, placer, blockNearby()), "minecraft:iron_ingot", 1);
            throw new IllegalStateException("smeltless ingots must fail planning");
        } catch (com.bhautik.mcagent.planner.Planner.PlanningException expected) {
            assertContains(expected.getMessage(), "no supported acquisition strategy");
        }


        int[] ingots = {0};
        boolean[] furnaceNear = {true};
        com.bhautik.mcagent.action.SmeltAction smelt =
                new com.bhautik.mcagent.action.SmeltAction(
                        "minecraft:raw_iron", "minecraft:coal", 1, () -> ingots[0],
                        okSmelter(),
                        () -> furnaceNear[0]);
        smelt.start();
        assertEquals(smelt.status(), ActionStatus.RUNNING, "smelt starts running");
        ingots[0] = 1;
        smelt.tick();
        assertEquals(smelt.status(), ActionStatus.SUCCESS, "smelt verified by inventory");
        com.bhautik.mcagent.action.SmeltAction lostFurnace =
                new com.bhautik.mcagent.action.SmeltAction(
                        "minecraft:raw_iron", "minecraft:coal", 1, () -> 0,
                        okSmelter(),
                        () -> false);
        lostFurnace.start();
        assertEquals(lostFurnace.status(), ActionStatus.FAILED,
                "missing furnace fails fast");

        // Drifting away mid-cook is tolerated: the furnace cooks on its
        // own, so only the cook-time timeout may end the run.
        boolean[] driftFurnaceNear = {true};
        int[] cooked = {0};
        com.bhautik.mcagent.action.SmeltAction drifts =
                new com.bhautik.mcagent.action.SmeltAction(
                        "minecraft:raw_iron", "minecraft:coal", 1, () -> cooked[0],
                        okSmelter(),
                        () -> driftFurnaceNear[0]);
        drifts.start();
        assertEquals(drifts.status(), ActionStatus.RUNNING, "smelt starts running");
        drifts.tick();
        assertEquals(drifts.status(), ActionStatus.RUNNING, "furnace loaded");
        driftFurnaceNear[0] = false;
        for (int i = 0; i <= com.bhautik.mcagent.action.SmeltAction.WARMUP_TICKS
                + com.bhautik.mcagent.action.SmeltAction.COOK_TICKS_PER_ITEM + 2; i++) {
            drifts.tick();
        }
        assertEquals(drifts.status(), ActionStatus.FAILED, "timeout ends a dead run");
        assertContains(String.valueOf(drifts.failureReason()), "produced no output");

        // Gated crafting fails honestly when the environment check dies.
        CraftAction gated = new CraftAction(recipes.get("minecraft:chest"), 1,
                () -> 0, crafter, () -> false);
        gated.start();
        assertEquals(gated.status(), ActionStatus.FAILED, "missing environment fails fast");
        assertContains(String.valueOf(gated.failureReason()), "crafting table");

        boolean[] tableInWorld = {true};
        CraftAction gatedThenLost = new CraftAction(recipes.get("minecraft:chest"), 1,
                () -> 0, crafter, () -> tableInWorld[0]);
        gatedThenLost.start();
        tableInWorld[0] = false;
        gatedThenLost.tick();
        assertEquals(gatedThenLost.status(), ActionStatus.FAILED, "lost table fails mid-run");

        // Collection verifies the item actually returned to inventory.
        int[] tables = {0};
        com.bhautik.mcagent.action.BreakBlockAction collect =
                new com.bhautik.mcagent.action.BreakBlockAction(
                        "minecraft:crafting_table",
                        itemId -> {
                            tables[0] = 1; // world cleared + item granted
                            return com.bhautik.mcagent.action.BreakBlockAction.Breaker.Result.ok();
                        },
                        () -> tables[0]);
        collect.start();
        assertEquals(collect.status(), ActionStatus.RUNNING, "collect starts running");
        collect.tick();
        assertEquals(collect.status(), ActionStatus.SUCCESS, "collect verified by inventory");

        // A breaker that never succeeds fails honestly with its reason.
        com.bhautik.mcagent.action.BreakBlockAction stuck =
                new com.bhautik.mcagent.action.BreakBlockAction(
                        "minecraft:crafting_table",
                        itemId -> com.bhautik.mcagent.action.BreakBlockAction.Breaker.Result
                                .failed("no crafting_table within reach to collect"),
                        () -> 0);
        stuck.start();
        for (int i = 0; i <= com.bhautik.mcagent.action.BreakBlockAction.MAX_BREAK_ATTEMPTS; i++) {
            stuck.tick();
        }
        assertEquals(stuck.status(), ActionStatus.FAILED, "stuck collect fails");
        assertContains(String.valueOf(stuck.failureReason()), "within reach");

        // Torch upkeep: a mining plan with low torch stock crafts torches
        // BEFORE the goal's own digging begins.
        List<AgentAction> unlitPlan = planner.planAcquisition(resolver,
                id -> 0, Set.of("minecraft:wooden_pickaxe"), id -> 0,
                env(crafter, placer, blockNearby()), "minecraft:cobblestone", 3);
        int torchCraftIndex = -1;
        for (int i = 0; i < unlitPlan.size(); i++) {
            if (unlitPlan.get(i) instanceof CraftAction
                    && unlitPlan.get(i).title().contains("torch")) {
                torchCraftIndex = i;
            }
        }
        int goalMiningIndex = -1;
        for (int i = 0; i < unlitPlan.size(); i++) {
            if (unlitPlan.get(i) instanceof MineAction
                    && unlitPlan.get(i).title().contains("stone")) {
                goalMiningIndex = i;
                break;
            }
        }
        if (torchCraftIndex < 0 || goalMiningIndex < 0 || torchCraftIndex > goalMiningIndex) {
            throw new IllegalStateException("expected torches crafted before goal mining, got "
                    + unlitPlan.stream().map(AgentAction::title).toList());
        }

        // Biome-gated gathering chains exploration before the dig, and
        // skips it entirely when already standing in the right biome.
        String[] currentBiome = {"minecraft:plains"};
        List<AgentAction> cactusPlan = planner.planAcquisition(resolver,
                torchStocked(), Set.of(), id -> 0,
                env(crafter, placer, com.bhautik.mcagent.world.BlockLocator.NONE,
                        output -> Optional.empty(),
                        () -> currentBiome[0], 12, 34),
                "minecraft:cactus", 4);
        assertEquals(cactusPlan.get(0).getClass(),
                com.bhautik.mcagent.action.ExploreAction.class,
                "cactus in plains starts with exploration");
        assertContains(cactusPlan.get(0).title(), "desert");
        assertEquals(cactusPlan.get(1).getClass(), MineAction.class,
                "then mines the gated source");
        com.bhautik.mcagent.action.ExploreAction walk =
                (com.bhautik.mcagent.action.ExploreAction) cactusPlan.get(0);
        walk.start();
        assertEquals(walk.status(), ActionStatus.RUNNING, "explore runs while away");

        currentBiome[0] = "minecraft:desert";
        List<AgentAction> localCactusPlan = planner.planAcquisition(resolver,
                torchStocked(), Set.of(), id -> 0,
                env(crafter, placer, com.bhautik.mcagent.world.BlockLocator.NONE,
                        output -> Optional.empty(),
                        () -> currentBiome[0], 12, 34),
                "minecraft:cactus", 4);
        assertEquals(localCactusPlan.get(0).getClass(), MineAction.class,
                "already-in-biome digs immediately");

        // Traveling counts as mining progress: a long search must not
        // restart Baritone every 30s (that resets the break in progress).
        com.bhautik.mcagent.world.PositionAnchor wanderAnchor =
                new com.bhautik.mcagent.world.PositionAnchor() {
                    int step;
                    @Override public int x() { return ++step * 10; }
                    @Override public int z() { return 0; }
                };
        FakeBackend travelBackend = new FakeBackend();
        int[] equips = {0};
        MineAction traveler = new MineAction("minecraft:iron_ore", 0, 5, () -> 0,
                travelBackend, "minecraft:diamond_pickaxe", wanderAnchor,
                itemId -> {
                    equips[0]++;
                    return true;
                });
        traveler.start();
        for (int i = 0; i <= MineAction.IDLE_TIMEOUT_TICKS; i++) {
            traveler.tick();
        }
        assertEquals(travelBackend.mineCalls, 1,
                "moving agent keeps its mining request alive");
        assertEquals(equips[0], 1, "equip happens once per action");

        // Tool churn guard: a sufficient held pickaxe tier means no swap.
        if (!DirectAcquisitions.pickaxeTierAtLeast(
                "minecraft:stone_pickaxe", "minecraft:wooden_pickaxe")) {
            throw new IllegalStateException("stone must satisfy wood tier");
        }
        if (DirectAcquisitions.pickaxeTierAtLeast(
                "minecraft:wooden_pickaxe", "minecraft:iron_pickaxe")) {
            throw new IllegalStateException("wood must not satisfy iron tier");
        }
        if (DirectAcquisitions.pickaxeTierAtLeast(null, "minecraft:iron_pickaxe")
                || DirectAcquisitions.pickaxeTierAtLeast("minecraft:bread",
                        "minecraft:iron_pickaxe")) {
            throw new IllegalStateException("non-pickaxes have no tier");
        }

    }

    /** Builds a planner environment around a given world-block state. */
    private static com.bhautik.mcagent.planner.Planner.Environment env(
            CraftAction.Crafter crafter, PlaceBlockAction.Placer placer,
            com.bhautik.mcagent.world.BlockLocator locator) {
        return env(crafter, placer, locator, output -> Optional.empty());
    }

    /** Environment variant with a fake smelting index. */
    private static com.bhautik.mcagent.planner.Planner.Environment env(
            CraftAction.Crafter crafter, PlaceBlockAction.Placer placer,
            com.bhautik.mcagent.world.BlockLocator locator,
            com.bhautik.mcagent.crafting.SmeltingResolver smelting) {
        return env(crafter, placer, locator, smelting,
                () -> "minecraft:plains", 5, 7);
    }

    /** Environment variant with a controllable fluid handler. */
    private static com.bhautik.mcagent.planner.Planner.Environment envWithFluids(
            CraftAction.Crafter crafter, PlaceBlockAction.Placer placer,
            com.bhautik.mcagent.world.BlockLocator locator,
            com.bhautik.mcagent.action.FluidHandler fluids) {
        var base = env(crafter, placer, locator);
        return new com.bhautik.mcagent.planner.Planner.Environment(
                base.crafter(), base.smelter(), base.placer(), base.breaker(),
                base.resolver(), base.smeltingResolver(), base.tableLocator(),
                base.furnaceLocator(), base.enchantTableLocator(),
                base.distanceSensor(), base.biomeSensor(), base.anchor(),
                base.equipper(), base.hunter(), base.xpSensor(), base.breeder(),
                fluids, base.obsidianBlockCount(), base.nearbyBlockCount(), base.farmer(), base.farmSite(), base.structureBuilder());
    }

    /** Environment variant with a controllable biome and anchor. */
    private static com.bhautik.mcagent.planner.Planner.Environment env(
            CraftAction.Crafter crafter, PlaceBlockAction.Placer placer,
            com.bhautik.mcagent.world.BlockLocator locator,
            com.bhautik.mcagent.crafting.SmeltingResolver smelting,
            java.util.function.Supplier<String> biome, int anchorX, int anchorZ) {
        return new com.bhautik.mcagent.planner.Planner.Environment(
                crafter,
                okSmelter(),
                placer,
                itemId -> com.bhautik.mcagent.action.BreakBlockAction.Breaker.Result.ok(),
                new com.bhautik.mcagent.crafting.RecipeResolver() {
                    @Override public Grid grid() { return Grid.INVENTORY_2X2; }
                    @Override public Optional<CraftableRecipe> findRecipe(String id) {
                        return Optional.empty();
                    }
                },
                smelting,
                locator, locator, locator, (x, y, z) -> 100.0,
                biome::get,
                new com.bhautik.mcagent.world.PositionAnchor() {
                    @Override public int x() { return anchorX; }
                    @Override public int z() { return anchorZ; }
                },
                itemId -> true,
                com.bhautik.mcagent.action.Hunter.NONE,
                com.bhautik.mcagent.world.XpSensor.NONE,
                com.bhautik.mcagent.action.Breeder.NONE,
                com.bhautik.mcagent.action.FluidHandler.NONE,
                () -> 0,
                blockId -> 0,
                com.bhautik.mcagent.action.Farmer.NONE,
                null,
                com.bhautik.mcagent.action.StructureBuilder.NONE);
    }

    /** A smelter that always reports success. */
    private static com.bhautik.mcagent.action.SmeltAction.Smelter okSmelter() {
        return new com.bhautik.mcagent.action.SmeltAction.Smelter() {
            @Override
            public com.bhautik.mcagent.action.SmeltAction.Smelter.Result begin(
                    String inputItemId, int inputCount, String fuelItemId, int fuelCount) {
                return com.bhautik.mcagent.action.SmeltAction.Smelter.Result.ok();
            }

            @Override
            public com.bhautik.mcagent.action.SmeltAction.Smelter.Result harvest() {
                return com.bhautik.mcagent.action.SmeltAction.Smelter.Result.ok();
            }
        };
    }

    /** A table right next to the agent. */
    private static com.bhautik.mcagent.world.BlockLocator blockNearby() {
        return new com.bhautik.mcagent.world.BlockLocator() {
            @Override public boolean isNearby() { return true; }
            @Override public Optional<com.bhautik.mcagent.world.BlockLocator.BlockSite>
                    nearestWithin(int radius) {
                return Optional.of(new com.bhautik.mcagent.world.BlockLocator.BlockSite(1, 64, 0));
            }
        };
    }

    /** A table too far to use but close enough to walk to. */
    private static com.bhautik.mcagent.world.BlockLocator blockFarAway() {
        return new com.bhautik.mcagent.world.BlockLocator() {
            @Override public boolean isNearby() { return false; }
            @Override public Optional<com.bhautik.mcagent.world.BlockLocator.BlockSite>
                    nearestWithin(int radius) {
                return radius >= 48
                        ? Optional.of(new com.bhautik.mcagent.world.BlockLocator.BlockSite(30, 70, -12))
                        : Optional.empty();
            }
        };
    }

    /** Counts lambda that suppresses torch upkeep in legacy scenarios. */
    private static java.util.function.Function<String, Integer> torchStocked() {
        return id -> ("minecraft:torch".equals(id)
                || com.bhautik.mcagent.planner.Planner.FOOD_UPKEEP_ITEM.equals(id)) ? 99 : 0;
    }

    /**
     * M8 checks: recovery waits out emergencies, eats while waiting,
     * times out honestly when nothing helps, and the executor suspends
     * (not cancels) running actions so they can be re-queued.
     */
    private static void validateSurvivalInterruptions() {
        boolean[] emergency = {true};
        int[] meals = {0};
        com.bhautik.mcagent.action.RecoverAction recover =
                new com.bhautik.mcagent.action.RecoverAction(
                        () -> emergency[0]
                                ? com.bhautik.mcagent.survival.Threat.emergency("health critical")
                                : com.bhautik.mcagent.survival.Threat.NONE,
                        () -> {
                            meals[0]++;
                            return 4;
                        });
        recover.start();
        assertEquals(recover.status(), ActionStatus.RUNNING, "recovery starts");
        for (int i = 0; i < 40 && recover.status() == ActionStatus.RUNNING; i++) {
            if (i == 5) {
                emergency[0] = false; // food kicks in, health recovers
            }
            recover.tick();
        }
        assertEquals(recover.status(), ActionStatus.SUCCESS, "recovers once emergency ends");
        if (meals[0] == 0) {
            throw new IllegalStateException("recovery must keep eating while critical");
        }

        // Starving with no food: timeout fails with an honest reason.
        com.bhautik.mcagent.action.RecoverAction starving =
                new com.bhautik.mcagent.action.RecoverAction(
                        () -> com.bhautik.mcagent.survival.Threat.emergency("starving"),
                        () -> 0);
        starving.start();
        for (int i = 0; i <= com.bhautik.mcagent.action.RecoverAction.TIMEOUT_TICKS + 1; i++) {
            starving.tick();
        }
        assertEquals(starving.status(), ActionStatus.FAILED, "unrecoverable fails on timeout");
        assertContains(String.valueOf(starving.failureReason()), "no edible food");

        // Drowning recovery: keeps swimming until the monitor clears.
        boolean[] airOk = {false};
        int[] strokes = {0};
        com.bhautik.mcagent.action.SurfaceAction surfacing =
                new com.bhautik.mcagent.action.SurfaceAction(
                        () -> airOk[0] ? com.bhautik.mcagent.survival.Threat.NONE
                                : com.bhautik.mcagent.survival.Threat.airEmergency("air critical"),
                        () -> {
                            strokes[0]++;
                            return true;
                        });
        surfacing.start();
        assertEquals(surfacing.status(), ActionStatus.RUNNING, "surfacing starts");
        for (int i = 0; i < 30 && surfacing.status() == ActionStatus.RUNNING; i++) {
            if (i == 10) {
                airOk[0] = true; // head above water
            }
            surfacing.tick();
        }
        assertEquals(surfacing.status(), ActionStatus.SUCCESS, "surfaces once breathable");
        if (strokes[0] == 0) {
            throw new IllegalStateException("surface action must keep swimming");
        }

        // Trapped underwater: timeout fails honestly.
        com.bhautik.mcagent.action.SurfaceAction trapped =
                new com.bhautik.mcagent.action.SurfaceAction(
                        () -> com.bhautik.mcagent.survival.Threat.airEmergency("air critical"),
                        () -> false);
        trapped.start();
        for (int i = 0; i <= com.bhautik.mcagent.action.SurfaceAction.TIMEOUT_TICKS + 1; i++) {
            trapped.tick();
        }
        assertEquals(trapped.status(), ActionStatus.FAILED, "unreachable air fails on timeout");
        assertContains(String.valueOf(trapped.failureReason()), "reach air");

        // Storage: deposits until nothing matches, verified by counts.
        java.util.List<String> storedIds = new java.util.ArrayList<>();
        com.bhautik.mcagent.action.DepositAction deposit =
                new com.bhautik.mcagent.action.DepositAction("Store at base",
                        List.of("minecraft:cobblestone"),
                        (ids, maxStacks) -> {
                            storedIds.addAll(ids);
                            return storedIds.size() > 3 ? 0 : 2; // 3 batches then empty
                        });
        deposit.start();
        for (int i = 0; i < 5 && deposit.status() == ActionStatus.RUNNING; i++) {
            deposit.tick();
        }
        assertEquals(deposit.status(), ActionStatus.SUCCESS, "deposit completes");
        assertEquals(deposit.storedStacks(), 6, "all batches counted");

        // Restock: withdraws until the chest stops giving, verified by
        // moved-stack counts.
        int[] chestStock = {3};
        com.bhautik.mcagent.action.WithdrawAction withdraw =
                new com.bhautik.mcagent.action.WithdrawAction("Restock torches",
                        List.of("minecraft:torch"),
                        (ids, maxStacks) -> {
                            int give = Math.min(maxStacks, chestStock[0]);
                            chestStock[0] -= give;
                            return give;
                        });
        withdraw.start();
        for (int i = 0; i < 5 && withdraw.status() == ActionStatus.RUNNING; i++) {
            withdraw.tick();
        }
        assertEquals(withdraw.status(), ActionStatus.SUCCESS, "withdraw completes");
        assertEquals(withdraw.withdrawnStacks(), 3, "all stocked stacks taken");

        // Base saved-state codec roundtrips through JSON ops.
        var state = com.bhautik.mcagent.integration.BaseSavedState.TYPE;
        com.bhautik.mcagent.integration.BaseSavedState persisted =
                new com.bhautik.mcagent.integration.BaseSavedState();
        persisted.setAnchor(12, 64, -7);
        persisted.setChest(14, 64, -9);
        var encoded = com.bhautik.mcagent.integration.BaseSavedState.CODEC.encodeStart(
                com.mojang.serialization.JsonOps.INSTANCE, persisted).getOrThrow();
        var decoded = com.bhautik.mcagent.integration.BaseSavedState.CODEC.parse(
                com.mojang.serialization.JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(decoded.anchor().length, 3, "anchor survives roundtrip");
        if (decoded.chest() == null || decoded.chest()[0] != 14) {
            throw new IllegalStateException("chest position lost in roundtrip");
        }

        // Combat v0 sword preference: best tier carried wins; bare hands
        // return empty (punching is a last resort, not a plan).
        var best = DirectAcquisitions.bestSwordFor(java.util.Set.of(
                "minecraft:wooden_sword", "minecraft:diamond_sword",
                "minecraft:iron_pickaxe"));
        assertEquals(best.orElseThrow(), "minecraft:diamond_sword",
                "highest-tier sword carried is chosen");
        if (DirectAcquisitions.bestSwordFor(java.util.Set.of(
                "minecraft:iron_pickaxe", "minecraft:bread")).isPresent()) {
            throw new IllegalStateException("no sword means no sword");
        }

        // Suspension pauses without cancelling: the action comes back
        // re-launchable and the backend was told to stop.
        FakeBackend survivalBackend = new FakeBackend();
        int[] ore = {0};
        MineAction minable = new MineAction("minecraft:diamond_ore", 0, 3, () -> ore[0],
                survivalBackend);
        com.bhautik.mcagent.executor.AgentExecutor exec =
                new com.bhautik.mcagent.executor.AgentExecutor(
                        new com.bhautik.mcagent.planner.Planner(survivalBackend),
                        survivalBackend);
        exec.launch(minable);
        assertEquals(exec.busy(), true, "action running before suspension");
        AgentAction suspended = exec.suspendCurrent("test emergency");
        assertEquals(exec.busy(), false, "executor idle after suspension");
        if (suspended == null || suspended != minable) {
            throw new IllegalStateException("suspend must return the paused action");
        }
        if (minable.status() != ActionStatus.RUNNING) {
            throw new IllegalStateException("pause must keep the action non-terminal");
        }
        // Relaunching the very same instance resumes ticking safely.
        exec.launch(minable);
        ore[0] = 3;
        exec.tick();
        assertEquals(minable.status(), ActionStatus.SUCCESS, "relaunched action completes");
    }

    /**
     * M9 checks: exploration verifies arrival against the live biome
     * sensor, re-issues on stalls, and fails honestly when the target
     * never turns up.
     */
    private static void validateExploration() {
        // Goal lifecycle: immediate success when already there.
        dev.minecraftai.agent.goal.ExploreGoal arrived =
                new dev.minecraftai.agent.goal.ExploreGoal("desert", () -> true);
        arrived.activate();
        assertEquals(arrived.status(), dev.minecraftai.agent.goal.GoalStatus.SUCCESS,
                "already-in-biome succeeds on activate");
        assertContains(arrived.progressReport(), "Target biome: desert");

        dev.minecraftai.agent.goal.ExploreGoal traveling =
                new dev.minecraftai.agent.goal.ExploreGoal("jungle", () -> false);
        traveling.activate();
        assertEquals(traveling.status(), dev.minecraftai.agent.goal.GoalStatus.ACTIVE,
                "travel goal starts active");
        traveling.markSuccess();
        if (traveling.status() != dev.minecraftai.agent.goal.GoalStatus.ACTIVE) {
            throw new IllegalStateException("markSuccess must respect the live check");
        }

        // Action: arrival verified by sensor, not backend claims.
        FakeBackend exploringBackend = new FakeBackend();
        String[] biome = {"minecraft:plains"};
        com.bhautik.mcagent.action.ExploreAction explore =
                new com.bhautik.mcagent.action.ExploreAction(
                        "minecraft:desert", 100, 200, () -> biome[0], exploringBackend);
        explore.start();
        assertEquals(explore.status(), ActionStatus.RUNNING, "exploring while away");
        biome[0] = "minecraft:desert";
        explore.tick();
        assertEquals(explore.status(), ActionStatus.SUCCESS, "arrival verified by biome");

        // Never arriving exhausts the retry ladder honestly.
        com.bhautik.mcagent.action.ExploreAction hopeless =
                new com.bhautik.mcagent.action.ExploreAction(
                        "minecraft:mushroom_fields", 0, 0, () -> "minecraft:plains",
                        new FakeBackend());
        hopeless.start();
        for (int i = 0; i <= com.bhautik.mcagent.action.ExploreAction.IDLE_TIMEOUT_TICKS
                * com.bhautik.mcagent.action.ExploreAction.MAX_ISSUE_ATTEMPTS; i++) {
            hopeless.tick();
        }
        assertEquals(hopeless.status(), ActionStatus.FAILED,
                "unfound biome fails after full budget");
        assertContains(String.valueOf(hopeless.failureReason()), "not reached");
    }

    private static SlotSpec cell(String itemId) {
        return new SlotSpec(List.of(itemId));
    }

    private static CraftableRecipe recipe(String output, int resultCount, SlotSpec... slots) {
        return new CraftableRecipe(output, resultCount, slots.length, 1, false, List.of(slots));
    }

    private static CraftableRecipe tableRecipe(String output, int resultCount, SlotSpec... slots) {
        int width = 3;
        int height = (slots.length + width - 1) / width;
        java.util.List<SlotSpec> cells = new ArrayList<>(List.of(slots));
        while (cells.size() < width * height) {
            cells.add(SlotSpec.EMPTY);
        }
        return new CraftableRecipe(output, resultCount, width, height, true, cells);
    }

    /**
     * Slice E1 (docs/ENCHANTING.md): the XP seam the enchant readiness
     * check and the later XP farm both read. Curve values are the
     * vanilla points-to-level totals, so "N points to go" is honest.
     */
    /**
     * Mob drops (E3): leather has no source block, so the planner must
     * route it through a hunt instead of failing with "no supported
     * acquisition strategy" — which is what blocked the enchanting-table
     * chain before cow hunting existed.
     */
    private static void validateHunting() {
        com.bhautik.mcagent.planner.Planner planner =
                new com.bhautik.mcagent.planner.Planner(new FakeBackend());
        Map<String, CraftableRecipe> recipes = new HashMap<>();
        recipes.put("minecraft:paper", recipe("minecraft:paper", 3,
                cell("minecraft:sugar_cane"), cell("minecraft:sugar_cane"),
                cell("minecraft:sugar_cane")));
        recipes.put("minecraft:book", recipe("minecraft:book", 1,
                cell("minecraft:paper"), cell("minecraft:paper"),
                cell("minecraft:paper"), cell("minecraft:leather")));
        com.bhautik.mcagent.crafting.RecipeResolver resolver =
                new com.bhautik.mcagent.crafting.RecipeResolver() {
                    @Override public Grid grid() { return Grid.INVENTORY_2X2; }
                    @Override public Optional<CraftableRecipe> findRecipe(String id) {
                        return Optional.ofNullable(recipes.get(id));
                    }
                };
        CraftAction.Crafter crafter = (recipe, times) -> times;
        PlaceBlockAction.Placer placer = itemId -> PlaceBlockAction.Placer.Result.ok();
        var environment = env(crafter, placer,
                com.bhautik.mcagent.world.BlockLocator.NONE);

        // leather itself: one hunt step, no mining.
        List<AgentAction> leatherPlan = planner.planAcquisition(resolver,
                torchStocked(), Set.of(), id -> 0, environment,
                "minecraft:leather", 3);
        assertEquals(leatherPlan.size(), 1, "leather plan length");
        if (!(leatherPlan.get(0) instanceof com.bhautik.mcagent.action.HuntAction hunt)) {
            throw new IllegalStateException("expected a hunt step, got "
                    + leatherPlan.get(0).title());
        }
        assertContains(hunt.title(), "cow");
        // A hunt that has not yet exhausted its roam stays retryable; an
        // empty scan must no longer fail the action on the spot.
        if (!hunt.retryable()) {
            throw new IllegalStateException("fresh hunt should be retryable");
        }
        hunt.start();
        assertEquals(hunt.status(), com.bhautik.mcagent.action.ActionStatus.RUNNING,
                "hunt survives an empty scan and roams");

        // book: the full mixed chain - mine sugar cane, craft paper,
        // hunt leather, craft the book.
        List<AgentAction> bookPlan = planner.planAcquisition(resolver,
                torchStocked(), Set.of(), id -> 0, environment,
                "minecraft:book", 1);
        boolean hunts = bookPlan.stream()
                .anyMatch(step -> step instanceof com.bhautik.mcagent.action.HuntAction);
        if (!hunts) {
            throw new IllegalStateException("book chain never hunts leather: "
                    + bookPlan.stream().map(AgentAction::title).toList());
        }
        boolean crafts = bookPlan.stream().anyMatch(step -> step instanceof CraftAction);
        if (!crafts) {
            throw new IllegalStateException("book chain never crafts");
        }

        // The planner reports what it demanded, so callers can withdraw
        // only the stored supplies a plan actually uses (a leather goal
        // must not haul the whole base chest along).
        Set<String> demanded = new java.util.LinkedHashSet<>();
        planner.planAcquisition(resolver, torchStocked(), Set.of(), id -> 0,
                environment, List.of(Map.entry("minecraft:leather", 3)), demanded);
        if (!demanded.contains("minecraft:leather")) {
            throw new IllegalStateException("demanded set missing the goal item: " + demanded);
        }
        if (demanded.contains("minecraft:cobblestone")) {
            throw new IllegalStateException(
                    "leather plan must not demand cobblestone: " + demanded);
        }

        // Regression: when the only animals left are breeding stock, the
        // action must keep ONE roam running. Resetting search state for
        // spared prey restarted the roam every tick, which re-issued
        // navigation constantly and stopped the timeout ever advancing.
        FakeBackend roamBackend = new FakeBackend();
        com.bhautik.mcagent.action.Hunter sparedOnly =
                new com.bhautik.mcagent.action.Hunter() {
                    @Override public Optional<MobSite> nearest(String mob, int radius) {
                        return Optional.of(new MobSite(1, 2, 3));
                    }
                    @Override public boolean strike(String mob, double reach) {
                        return false;
                    }
                    @Override public int countNearby(String mob, int radius) {
                        return com.bhautik.mcagent.action.HuntAction.BREEDING_STOCK;
                    }
                };
        var spareRun = new com.bhautik.mcagent.action.HuntAction(
                "minecraft:cow", "minecraft:leather", 3, () -> 0, sparedOnly,
                roamBackend,
                new com.bhautik.mcagent.world.PositionAnchor() {
                    @Override public int x() { return 0; }
                    @Override public int z() { return 0; }
                });
        spareRun.start();
        for (int tick = 0; tick < 40; tick++) {
            spareRun.tick();
        }
        assertIntEquals(roamBackend.exploreCalls, 1,
                "roam must issue navigation once, not every tick");
        assertEquals(spareRun.status(), com.bhautik.mcagent.action.ActionStatus.RUNNING,
                "spared-herd roam keeps running");

        // Regression: after a kill the agent must detour to the drop
        // instead of chasing the next animal, or it leaves the leather
        // it just earned lying on the ground.
        FakeBackend killBackend = new FakeBackend();
        int[] liveHerd = {5};
        com.bhautik.mcagent.action.Hunter shrinkingHerd =
                new com.bhautik.mcagent.action.Hunter() {
                    @Override public Optional<MobSite> nearest(String mob, int radius) {
                        return Optional.of(new MobSite(10, 64, 10));
                    }
                    @Override public boolean strike(String mob, double reach) {
                        return true; // always in reach and swinging
                    }
                    @Override public int countNearby(String mob, int radius) {
                        return liveHerd[0];
                    }
                };
        var killRun = new com.bhautik.mcagent.action.HuntAction(
                "minecraft:cow", "minecraft:leather", 3, () -> 0, shrinkingHerd,
                killBackend,
                new com.bhautik.mcagent.world.PositionAnchor() {
                    @Override public int x() { return 0; }
                    @Override public int z() { return 0; }
                });
        killRun.start();
        killRun.tick();          // strike; records the kill site
        liveHerd[0] = 4;         // the cow died
        int gotoBefore = killBackend.gotoCalls;
        killRun.tick();          // must now path to the drop, not re-strike
        if (killBackend.gotoCalls <= gotoBefore) {
            throw new IllegalStateException(
                    "kill did not trigger a detour to collect the drop");
        }

        // Regression: every mining plan gets torch upkeep, including the
        // enchant path. It shipped without any, so enchant chains dug for
        // lapis and obsidian with no torches.
        Map<String, CraftableRecipe> upkeepRecipes = new HashMap<>(recipes);
        upkeepRecipes.put("minecraft:torch", recipe("minecraft:torch", 4,
                cell("minecraft:coal"), cell("minecraft:stick")));
        upkeepRecipes.put("minecraft:stick", recipe("minecraft:stick", 4,
                cell("minecraft:oak_planks"), cell("minecraft:oak_planks")));
        upkeepRecipes.put("minecraft:oak_planks", recipe("minecraft:oak_planks", 4,
                cell("minecraft:oak_log")));
        upkeepRecipes.put("minecraft:enchanting_table",
                tableRecipe("minecraft:enchanting_table", 1,
                        cell("minecraft:book"), cell("minecraft:diamond"),
                        cell("minecraft:obsidian")));
        com.bhautik.mcagent.crafting.RecipeResolver upkeepResolver =
                new com.bhautik.mcagent.crafting.RecipeResolver() {
                    @Override public Grid grid() { return Grid.INVENTORY_2X2; }
                    @Override public Optional<CraftableRecipe> findRecipe(String id) {
                        return Optional.ofNullable(upkeepRecipes.get(id));
                    }
                };
        // The agent already carries the item to enchant, a table to place
        // and a pickaxe; only lapis has to be mined, which is what makes
        // this a mining plan and so demands upkeep.
        java.util.function.Function<String, Integer> carried = id -> switch (id) {
            case "minecraft:diamond_sword", "minecraft:diamond_pickaxe",
                 "minecraft:enchanting_table" -> 1;
            default -> 0;
        };
        List<AgentAction> enchantPlan = planner.planEnchant(
                upkeepResolver, carried, Set.of("minecraft:diamond_pickaxe"),
                id -> 0, environment,
                (item, level) -> com.bhautik.mcagent.action.EnchantAction.Enchanter
                        .Result.ok(),
                () -> 0, "minecraft:diamond_sword", 1, null, new java.util.LinkedHashSet<>());
        boolean topsUpTorches = enchantPlan.stream()
                .anyMatch(step -> step.title().contains("torch"));
        if (!topsUpTorches) {
            throw new IllegalStateException("enchant plan skipped torch upkeep: "
                    + enchantPlan.stream().map(AgentAction::title).toList());
        }

        // Obsidian is made from lava when a source is in range, and only
        // searched for when none is. Natural obsidian is scarce, so
        // mining for it is mostly walking.
        com.bhautik.mcagent.action.FluidHandler lavaNearby =
                new com.bhautik.mcagent.action.FluidHandler() {
                    @Override public Optional<com.bhautik.mcagent.world.BlockLocator.BlockSite>
                            nearest(String fluidId, int radius) {
                        return com.bhautik.mcagent.action.MakeObsidianAction.LAVA.equals(fluidId)
                                ? Optional.of(new com.bhautik.mcagent.world.BlockLocator
                                        .BlockSite(5, 60, 5))
                                : Optional.empty();
                    }
                    @Override public boolean fillFrom(
                            com.bhautik.mcagent.world.BlockLocator.BlockSite site) {
                        return true;
                    }
                    @Override public boolean pourOnto(
                            com.bhautik.mcagent.world.BlockLocator.BlockSite site) {
                        return true;
                    }
                    @Override public boolean carriesWater() {
                        return false;
                    }
                    @Override public Optional<com.bhautik.mcagent.world.BlockLocator.BlockSite>
                            nearestPourable(int radius) {
                        return Optional.of(new com.bhautik.mcagent.world.BlockLocator
                                .BlockSite(5, 60, 5));
                    }
                };
        var lavaEnv = envWithFluids(crafter, placer,
                com.bhautik.mcagent.world.BlockLocator.NONE, lavaNearby);
        List<AgentAction> madePlan = planner.planAcquisition(resolver,
                id -> id.equals("minecraft:bucket") ? 1 : 0,
                Set.of("minecraft:diamond_pickaxe"), id -> 0, lavaEnv,
                "minecraft:obsidian", 4);
        var makeStep = madePlan.stream()
                .filter(step -> step instanceof com.bhautik.mcagent.action.MakeObsidianAction)
                .findFirst().orElse(null);
        if (makeStep == null) {
            throw new IllegalStateException("obsidian plan never makes it from lava: "
                    + madePlan.stream().map(AgentAction::title).toList());
        }
        // The plan keeps a mine step behind it, so a failed pour must fall
        // through to mining rather than sinking the goal.
        if (!makeStep.bestEffort()) {
            throw new IllegalStateException("make-obsidian must be best-effort so the"
                    + " mining fallback still runs");
        }
        boolean keepsMiningFallback = madePlan.stream()
                .anyMatch(step -> step.title().contains("Mine") && step.title().contains("obsidian"));
        if (!keepsMiningFallback) {
            throw new IllegalStateException("no mining fallback behind make-obsidian: "
                    + madePlan.stream().map(AgentAction::title).toList());
        }
        // Without lava it falls back to mining whatever generated naturally.
        List<AgentAction> minedPlan = planner.planAcquisition(resolver,
                id -> 0, Set.of("minecraft:diamond_pickaxe"), id -> 0, environment,
                "minecraft:obsidian", 4);
        boolean fallsBack = minedPlan.stream()
                .noneMatch(step -> step instanceof com.bhautik.mcagent.action.MakeObsidianAction);
        if (!fallsBack) {
            throw new IllegalStateException("obsidian should be mined when no lava is near");
        }

        // Vein mining: reaching the target with ore still exposed nearby
        // keeps digging (the walk there is the expensive part), but stops
        // once the vein is gone and never exceeds the bonus cap.
        FakeBackend veinBackend = new FakeBackend();
        int[] mined = {0};
        int[] exposed = {5};
        var veinRun = new com.bhautik.mcagent.action.MineAction(
                "minecraft:diamond_ore", 0, 2, () -> mined[0], veinBackend,
                null, null, null, () -> exposed[0]);
        veinRun.start();
        mined[0] = 2;            // goal met, but 5 ore still exposed
        veinRun.tick();
        assertEquals(veinRun.status(), com.bhautik.mcagent.action.ActionStatus.RUNNING,
                "vein still exposed keeps mining");
        mined[0] = 7;            // took the vein
        exposed[0] = 0;          // nothing left
        veinRun.tick();
        assertEquals(veinRun.status(), com.bhautik.mcagent.action.ActionStatus.SUCCESS,
                "exhausted vein finishes the action");

        // With no ore nearby it stops exactly at the requested count.
        int[] lone = {0};
        var loneRun = new com.bhautik.mcagent.action.MineAction(
                "minecraft:diamond_ore", 0, 2, () -> lone[0], new FakeBackend(),
                null, null, null, () -> 0);
        loneRun.start();
        lone[0] = 2;
        loneRun.tick();
        assertEquals(loneRun.status(), com.bhautik.mcagent.action.ActionStatus.SUCCESS,
                "no vein nearby stops at the target");

        // The leather cycle closes without a village: wheat is GROWN
        // (seeds from grass, tilled, sown, reaped) rather than mined,
        // because wild wheat only generates in village farms.
        Map<String, CraftableRecipe> farmRecipes = new HashMap<>(recipes);
        farmRecipes.put("minecraft:wooden_hoe", tableRecipe("minecraft:wooden_hoe", 1,
                cell("minecraft:oak_planks"), cell("minecraft:oak_planks"), SlotSpec.EMPTY,
                SlotSpec.EMPTY, cell("minecraft:stick"), SlotSpec.EMPTY,
                SlotSpec.EMPTY, cell("minecraft:stick"), SlotSpec.EMPTY));
        farmRecipes.put("minecraft:oak_planks", recipe("minecraft:oak_planks", 4,
                cell("minecraft:oak_log")));
        farmRecipes.put("minecraft:stick", recipe("minecraft:stick", 4,
                cell("minecraft:oak_planks"), cell("minecraft:oak_planks")));
        // The hoe is a 3x3 recipe, so the plan needs a table to make it on.
        farmRecipes.put("minecraft:crafting_table", recipe("minecraft:crafting_table", 1,
                cell("minecraft:oak_planks"), cell("minecraft:oak_planks"),
                cell("minecraft:oak_planks"), cell("minecraft:oak_planks")));
        com.bhautik.mcagent.crafting.RecipeResolver farmResolver =
                new com.bhautik.mcagent.crafting.RecipeResolver() {
                    @Override public Grid grid() { return Grid.INVENTORY_2X2; }
                    @Override public Optional<CraftableRecipe> findRecipe(String id) {
                        return Optional.ofNullable(farmRecipes.get(id));
                    }
                };
        List<AgentAction> wheatPlan = planner.planAcquisition(farmResolver,
                torchStocked(), Set.of(), id -> 0, environment, "minecraft:wheat", 2);
        boolean farms = wheatPlan.stream()
                .anyMatch(step -> step instanceof com.bhautik.mcagent.action.FarmCropAction);
        if (!farms) {
            throw new IllegalStateException("wheat must be farmed, not mined: "
                    + wheatPlan.stream().map(AgentAction::title).toList());
        }
        // Seeds come from grass, so the chain starts from nothing.
        boolean getsSeeds = wheatPlan.stream()
                .anyMatch(step -> step.title().contains("short_grass"));
        if (!getsSeeds) {
            throw new IllegalStateException("wheat plan never gathers seeds: "
                    + wheatPlan.stream().map(AgentAction::title).toList());
        }

        // Regression: a build must refuse outright when anything valuable
        // is inside its footprint, BEFORE placing a single block. This
        // destroyed a base chest and everything in it.
        var farmPlan = com.bhautik.mcagent.build.Blueprints.wheatFarm();
        boolean[] everPlaced = {false};
        com.bhautik.mcagent.action.StructureBuilder guarded =
                new com.bhautik.mcagent.action.StructureBuilder() {
                    @Override public boolean place(
                            com.bhautik.mcagent.world.BlockLocator.BlockSite at,
                            String blockId) {
                        everPlaced[0] = true;
                        return true;
                    }
                    @Override public boolean clear(
                            com.bhautik.mcagent.world.BlockLocator.BlockSite at) {
                        everPlaced[0] = true;
                        return true;
                    }
                    @Override public String blockAt(
                            com.bhautik.mcagent.world.BlockLocator.BlockSite at) {
                        return "minecraft:chest";
                    }
                    @Override public boolean isProtected(
                            com.bhautik.mcagent.world.BlockLocator.BlockSite at) {
                        return at.x() == 2 && at.z() == 2; // one chest in the way
                    }
                };
        var guardedBuild = new com.bhautik.mcagent.action.BuildAction(farmPlan,
                new com.bhautik.mcagent.world.BlockLocator.BlockSite(0, 64, 0),
                guarded, new FakeBackend(), (x, y, z) -> 0.0);
        guardedBuild.start();
        assertEquals(guardedBuild.status(),
                com.bhautik.mcagent.action.ActionStatus.FAILED,
                "build must refuse a footprint containing a chest");
        if (everPlaced[0]) {
            throw new IllegalStateException(
                    "build touched the world before refusing - the survey must "
                            + "run first, or contents are already gone");
        }
        assertContains(guardedBuild.failureReason(), "refusing to build");
        if (guardedBuild.retryable()) {
            throw new IllegalStateException("a blocked site must not be retried");
        }

        // Reservations keep a second structure off the first one's ground.
        var firstFarm = com.bhautik.mcagent.build.Reservation.centredOn(
                "wheat_farm", farmPlan, 0, 64, 0);
        var onTop = com.bhautik.mcagent.build.Reservation.centredOn(
                "wheat_farm", farmPlan, 0, 64, 0);
        if (!firstFarm.overlaps(onTop)) {
            throw new IllegalStateException("identical footprints must overlap");
        }
        var touching = com.bhautik.mcagent.build.Reservation.centredOn(
                "wheat_farm", farmPlan, 9, 64, 0);
        if (!firstFarm.overlaps(touching)) {
            throw new IllegalStateException(
                    "adjacent footprints must overlap once the margin is counted");
        }
        var wellClear = com.bhautik.mcagent.build.Reservation.centredOn(
                "wheat_farm", farmPlan, 40, 64, 40);
        if (firstFarm.overlaps(wellClear)) {
            throw new IllegalStateException("distant footprints must not overlap");
        }
        // A farm's own ground counts as covered, so nothing is sited inside it.
        if (!firstFarm.covers(0, 64, 0)) {
            throw new IllegalStateException("a reservation must cover its own centre");
        }

        // Items with no block AND no mob source still fail honestly.
        try {
            planner.planAcquisition(resolver, torchStocked(), Set.of(), id -> 0,
                    environment, "minecraft:nether_star", 1);
            throw new IllegalStateException("expected planning to refuse nether_star");
        } catch (com.bhautik.mcagent.planner.Planner.PlanningException expected) {
            assertContains(expected.getMessage(), "no supported acquisition strategy");
        }
    }

    private static void validateXpSensing() {
        com.bhautik.mcagent.world.XpSensor none =
                com.bhautik.mcagent.world.XpSensor.NONE;
        assertIntEquals(none.level(), 0, "NONE level");
        assertIntEquals(none.totalPoints(), 0, "NONE points");

        com.bhautik.mcagent.world.XpSensor at30 =
                com.bhautik.mcagent.world.XpSensor.atLevel(30);
        assertIntEquals(at30.level(), 30, "atLevel(30) level");

        // Vanilla piecewise curve boundaries.
        assertIntEquals(com.bhautik.mcagent.world.XpSensor.pointsForLevel(0), 0, "level 0");
        assertIntEquals(com.bhautik.mcagent.world.XpSensor.pointsForLevel(1), 7, "level 1");
        assertIntEquals(com.bhautik.mcagent.world.XpSensor.pointsForLevel(16), 352, "level 16");
        assertIntEquals(com.bhautik.mcagent.world.XpSensor.pointsForLevel(17), 394, "level 17");
        assertIntEquals(com.bhautik.mcagent.world.XpSensor.pointsForLevel(30), 1395, "level 30");
        assertIntEquals(com.bhautik.mcagent.world.XpSensor.pointsForLevel(32), 1628, "level 32");
        assertIntEquals(com.bhautik.mcagent.world.XpSensor.MAX_ENCHANT_COST, 30, "max enchant cost");
    }

    private static void assertIntEquals(int actual, int expected, String label) {
        if (actual != expected) {
            throw new IllegalStateException(label + ": expected [" + expected + "] got [" + actual + "]");
        }
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
        String lastEquipped;
        int mineCalls;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public boolean startMine(String blockName, int quantity) {
            mineCalls++;
            return true;
        }

        int gotoCalls;

        @Override
        public boolean startGoTo(int x, int y, int z) {
            gotoCalls++;
            return true;
        }

        int exploreCalls;

        @Override
        public boolean startExplore(int centerX, int centerZ) {
            exploreCalls++;
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
