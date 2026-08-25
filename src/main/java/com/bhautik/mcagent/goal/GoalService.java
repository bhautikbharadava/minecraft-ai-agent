package com.bhautik.mcagent.goal;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.action.ActionStatus;
import com.bhautik.mcagent.action.AgentAction;
import com.bhautik.mcagent.action.RecoverAction;
import com.bhautik.mcagent.crafting.VanillaCraftingExecutor;
import com.bhautik.mcagent.crafting.VanillaRecipeResolver;
import com.bhautik.mcagent.executor.AgentExecutor;
import com.bhautik.mcagent.integration.VanillaSurvivalMonitor;
import com.bhautik.mcagent.state.InventoryState;
import com.bhautik.mcagent.survival.Threat;
import dev.minecraftai.agent.goal.AgentGoal;
import dev.minecraftai.agent.goal.AgentGoalManager;
import dev.minecraftai.agent.goal.ExploreGoal;
import dev.minecraftai.agent.goal.GetItemGoal;
import dev.minecraftai.agent.goal.GoalStatus;
import dev.minecraftai.agent.item.ItemRegistry;
import dev.minecraftai.agent.item.MinecraftItem;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent brain for get-item goals: registers goals, plans acquisition
 * actions, drives them from server ticks, verifies results against the
 * real inventory, and recovers from failures.
 */
public final class GoalService {
    private static final int PROGRESS_REFRESH_INTERVAL_TICKS = 20;
    private static final int MAX_PLAN_ATTEMPTS = 3;
    private static final int SURVIVAL_CHECK_INTERVAL_TICKS = 10;
    /** Chunk radius for vanilla structure searches (16 chunks = 256 blocks). */
    private static final int STRUCTURE_SEARCH_RADIUS_CHUNKS = 16;
    /** Within this distance (squared) of a located structure, we are there. */
    private static final double STRUCTURE_ARRIVE_DISTANCE_SQ = 48.0 * 48.0;
    /** Gatherable foods the agent plans for itself when starving. */
    private static final List<String> EMERGENCY_FOODS = List.of(
            "minecraft:sweet_berries", "minecraft:brown_mushroom",
            "minecraft:red_mushroom");
    private static final int EMERGENCY_FOOD_COUNT = 8;

    /** JVM-only fallback used by the CLI smoke checks; in-game resolution uses the live registry. */
    private final ItemRegistry itemRegistry = ItemRegistry.vanillaDefaults();
    private final AgentGoalManager goalManager = new AgentGoalManager();
    private final AgentExecutor executor;
    private final Object monitor = new Object();

    private ActiveRun run;

    public GoalService(AgentExecutor executor) {
        this.executor = executor;
    }

    public boolean isValidItem(String rawName) {
        return resolve(rawName).isPresent();
    }

    /**
     * Resolves shorthand ("diamond") or namespaced ids against the live
     * vanilla item registry, so every real item is requestable.
     */
    private Optional<MinecraftItem> resolve(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }
        String normalized = rawName.trim().toLowerCase();
        String qualified = normalized.contains(":") ? normalized : "minecraft:" + normalized;
        Identifier id = Identifier.tryParse(qualified);
        if (id == null) {
            return Optional.empty();
        }
        boolean exists = BuiltInRegistries.ITEM.get(id).isPresent();
        return exists ? Optional.of(new MinecraftItem(id.toString())) : Optional.empty();
    }

    public String getItem(ServerPlayer player, String rawItemName, int requestedCount) {
        synchronized (monitor) {
            if (goalManager.activeGoal().isPresent()) {
                return "A goal is already active. Use /agent goal to view it or /agent cancel first.";
            }
            MinecraftItem item = resolve(rawItemName).orElseThrow();
            dev.minecraftai.agent.world.InventoryState snapshot = snapshot(player);
            GetItemGoal goal = new GetItemGoal(item, requestedCount, snapshot);
            McAgent.LOGGER.info("[Agent] Goal created: {}", goal.title());
            goalManager.register(goal);
            if (goal.status() == GoalStatus.SUCCESS) {
                McAgent.LOGGER.info("[Agent] Goal completed immediately: {}", goal.title());
                return goal.progressReport();
            }
            run = new ActiveRun(goal, item, player.getUUID(), requestedCount,
                    snapshot, player.level().getServer(),
                    com.bhautik.mcagent.integration.VanillaSurvivalMonitor.monitor(player));
            if (!replan(run)) {
                return finishWithFailure(run);
            }
            // A missing navigation backend fails synchronously; surface it now
            // instead of promising work that already ended.
            var started = executor.pollFinished();
            if (started.isPresent()) {
                ActiveRun activeRun = run;
                run = null;
                activeRun.goal.markFailed(started.get().failureReason());
                McAgent.LOGGER.warn("[Agent] Goal failed immediately: {} ({})",
                        activeRun.goal.title(), activeRun.goal.failureReason());
                return activeRun.goal.progressReport();
            }
            return goal.progressReport()
                    + System.lineSeparator() + "Agent is working. Use /agent goal for progress.";
        }
    }

    public String describeActiveGoal() {
        synchronized (monitor) {
            return goalManager.describeActiveGoal();
        }
    }

    /** True when the named biome exists in the given world's registry. */
    public boolean isValidBiome(net.minecraft.world.level.Level level, String rawName) {
        return resolveBiome(level, rawName).isPresent();
    }

    private Optional<String> resolveBiome(net.minecraft.world.level.Level level,
                                          String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }
        String normalized = rawName.trim().toLowerCase();
        String qualified = normalized.contains(":") ? normalized : "minecraft:" + normalized;
        net.minecraft.resources.Identifier id = Identifier.tryParse(qualified);
        if (id == null) {
            return Optional.empty();
        }
        boolean exists = level.registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                .getOptional(id).isPresent();
        return exists ? Optional.of(qualified) : Optional.empty();
    }

    /** UC-08: travel to and verify a named biome. */
    public String explore(ServerPlayer player, String rawBiomeName) {
        synchronized (monitor) {
            if (goalManager.activeGoal().isPresent()) {
                return "A goal is already active. Use /agent goal to view it or /agent cancel first.";
            }
            String targetBiome = resolveBiome(player.level(), rawBiomeName)
                    .orElseThrow(() -> new IllegalArgumentException("invalid biome"));
            com.bhautik.mcagent.world.BiomeSensor biomeAt = () ->
                    player.level().getBiome(player.blockPosition()).unwrapKey()
                            .map(key -> key.identifier().toString()).orElse("");
            ExploreGoal goal = new ExploreGoal(
                    rawBiomeName.trim().toLowerCase(),
                    () -> targetBiome.equals(biomeAt.current()));
            McAgent.LOGGER.info("[Agent] Goal created: {}", goal.title());
            goalManager.register(goal);
            if (goal.status() == GoalStatus.SUCCESS) {
                McAgent.LOGGER.info("[Agent] Goal completed immediately: {}", goal.title());
                return goal.progressReport();
            }
            ActiveRun activeRun = new ActiveRun(goal, null, player.getUUID(), 0,
                    snapshot(player), player.level().getServer(),
                    com.bhautik.mcagent.integration.VanillaSurvivalMonitor.monitor(player));
            activeRun.exploreTargetBiome = targetBiome;
            activeRun.biomeAt = biomeAt;
            run = activeRun;
            if (!replan(run)) {
                return finishWithFailure(run);
            }
            var started = executor.pollFinished();
            if (started.isPresent()) {
                ActiveRun failedRun = run;
                run = null;
                failedRun.goal.markFailed(started.get().failureReason());
                McAgent.LOGGER.warn("[Agent] Goal failed immediately: {} ({})",
                        failedRun.goal.title(), failedRun.goal.failureReason());
                return failedRun.goal.progressReport();
            }
            return goal.progressReport()
                    + System.lineSeparator() + "Agent is exploring. Use /agent goal for progress.";
        }
    }

    /** M9: locate the nearest tagged structure and travel to it. */
    public String exploreStructure(ServerPlayer player, String rawName) {
        synchronized (monitor) {
            if (goalManager.activeGoal().isPresent()) {
                return "A goal is already active. Use /agent goal to view it or /agent cancel first.";
            }
            String tagId = com.bhautik.mcagent.world.StructureDirectory.tagFor(rawName)
                    .orElseThrow(() -> new IllegalArgumentException("unknown structure"));
            var level = (net.minecraft.server.level.ServerLevel) player.level();
            var tag = net.minecraft.tags.TagKey.create(
                    net.minecraft.core.registries.Registries.STRUCTURE,
                    Identifier.parse("minecraft:" + tagId));
            net.minecraft.core.BlockPos found = level.findNearestMapStructure(
                    tag, player.blockPosition(), STRUCTURE_SEARCH_RADIUS_CHUNKS, false);
            if (found == null) {
                return "No " + rawName + " found within "
                        + (STRUCTURE_SEARCH_RADIUS_CHUNKS * 16) + " blocks of here.";
            }
            net.minecraft.core.BlockPos target = found.immutable();
            McAgent.LOGGER.info("[Agent] Located {} at {} {} {}", rawName,
                    target.getX(), target.getY(), target.getZ());
            java.util.function.DoubleSupplier distance =
                    () -> player.distanceToSqr(target.getX(), target.getY(), target.getZ());
            ExploreGoal goal = new ExploreGoal(rawName.trim().toLowerCase(),
                    () -> distance.getAsDouble() <= STRUCTURE_ARRIVE_DISTANCE_SQ);
            McAgent.LOGGER.info("[Agent] Goal created: {}", goal.title());
            goalManager.register(goal);
            ActiveRun activeRun = new ActiveRun(goal, null, player.getUUID(), 0,
                    snapshot(player), player.level().getServer(),
                    com.bhautik.mcagent.integration.VanillaSurvivalMonitor.monitor(player));
            activeRun.structureTargetPos = target;
            activeRun.structureDistance = distance;
            run = activeRun;
            if (!replan(run)) {
                return finishWithFailure(run);
            }
            var started = executor.pollFinished();
            if (started.isPresent()) {
                ActiveRun failedRun = run;
                run = null;
                failedRun.goal.markFailed(started.get().failureReason());
                return failedRun.goal.progressReport();
            }
            return goal.progressReport() + System.lineSeparator()
                    + "Heading to " + rawName + " at " + target.getX() + " "
                    + target.getY() + " " + target.getZ() + ".";
        }
    }

    public String cancelActiveGoal() {
        synchronized (monitor) {
            if (run != null) {
                executor.cancelCurrent("user requested cancellation");
                run = null;
            }
            String report = goalManager.cancelActiveGoal();
            McAgent.LOGGER.info("[Agent] Goal cancelled: {}", report.replace("\n", " "));
            return report;
        }
    }

    /** Drives the active run from END_SERVER_TICK. Cheap no-op when idle. */
    public void serverTick(MinecraftServer server) {
        synchronized (monitor) {
            if (run == null) {
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(run.playerId);
            if (player == null) {
                abandonRun("player left the server");
                return;
            }

            run.tickCount++;
            if (run.tickCount % SURVIVAL_CHECK_INTERVAL_TICKS == 0) {
                handleSurvival(run, player);
            }
            if (run == null) {
                return;
            }
            executor.tick();

            if (run.tickCount % PROGRESS_REFRESH_INTERVAL_TICKS == 0) {
                refreshSnapshot(player);
            }

            executor.pollFinished().ifPresent(finished -> handleFinishedAction(player, finished));
        }
    }

    /**
     * Survival outranks the active goal (PRD 15): on emergency the
     * current action is paused and re-queued, and a recovery step jumps
     * the queue. When it succeeds, the original action relaunches fresh.
     */
    private void handleSurvival(ActiveRun activeRun, ServerPlayer player) {
        Threat threat = activeRun.survivalMonitor.assess();
        if (!threat.emergency() || activeRun.recovering || activeRun.securingFood) {
            // While gathering emergency food the interruption system
            // stands down — otherwise it would suspend the very steps
            // that fetch the food.
            return;
        }
        AgentAction suspended = executor.suspendCurrent(threat.reason());
        if (suspended != null) {
            activeRun.queue.addFirst(suspended);
        }
        AgentAction recovery = threat.needsAir()
                ? new com.bhautik.mcagent.action.SurfaceAction(activeRun.survivalMonitor,
                        VanillaSurvivalMonitor.swimmer(player))
                : new RecoverAction(activeRun.survivalMonitor,
                        VanillaSurvivalMonitor.feeder(player));
        activeRun.queue.addFirst(recovery);
        activeRun.recovering = true;
        McAgent.LOGGER.warn("[Recovery] Survival interrupt ({}); recovering before resuming",
                threat.reason());
        launchNextAction();
    }

    private void handleFinishedAction(ServerPlayer player, AgentAction finished) {
        ActiveRun activeRun = run;
        if (activeRun == null) {
            return;
        }
        refreshSnapshot(player);
        int current = activeRun.item == null ? 0
                : activeRun.snapshot.count(activeRun.item);
        // Reality first (PRD 14): if the goal is already satisfied, no
        // failure below matters.
        if (finished.status() != ActionStatus.CANCELLED && isSatisfied(activeRun, current)) {
            activeRun.goal.markSuccess();
            McAgent.LOGGER.info("[Agent] Goal completed: {}", activeRun.goal.title());
            run = null;
            return;
        }
        if (finished instanceof RecoverAction
                || finished instanceof com.bhautik.mcagent.action.SurfaceAction) {
            activeRun.recovering = false;
            if (finished.status() == ActionStatus.SUCCESS) {
                activeRun.needsEmergencyFood = false;
                activeRun.securingFood = false;
            }
            McAgent.LOGGER.info("[Recovery] Survival step finished ({}); resuming plan",
                    finished.status().toString().toLowerCase());
        }
        switch (finished.status()) {
            case CANCELLED -> {
                run = null;
            }
            case FAILED -> {
                McAgent.LOGGER.warn("[Recovery] Action failed: {} ({})",
                        finished.title(), finished.failureReason());
                if (finished instanceof RecoverAction
                        && String.valueOf(finished.failureReason())
                                .contains(com.bhautik.mcagent.action.RecoverAction.NO_FOOD_MARKER)) {
                    // Starving with empty pockets: the next plan gathers
                    // emergency food before trying to recover again.
                    activeRun.needsEmergencyFood = true;
                    McAgent.LOGGER.info("[Recovery] No food in inventory; planning foraged meals");
                }
                if (finished.bestEffort()) {
                    // Cleanup steps never cost an attempt or sink the goal.
                    McAgent.LOGGER.info("[Recovery] Best-effort step failed; continuing: {}",
                            finished.title());
                    advanceOrFinish(activeRun, current);
                } else if (activeRun.attempts < MAX_PLAN_ATTEMPTS && replan(activeRun)) {
                    McAgent.LOGGER.info("[Recovery] Retrying with a fresh plan");
                } else {
                    finishWithFailure(activeRun);
                }
            }
            default -> advanceOrFinish(activeRun, current);
        }
    }

    /** Moves to the next queued step, replans, or closes the run out. */
    private void advanceOrFinish(ActiveRun activeRun, int current) {
        if (!runQueueIsEmpty(activeRun) && launchNextAction()) {
            McAgent.LOGGER.info("[Planner] Advancing plan: {}", executor.currentTitle());
        } else if (activeRun.attempts < MAX_PLAN_ATTEMPTS && replan(activeRun)) {
            McAgent.LOGGER.info("[Recovery] Retrying with a fresh plan");
        } else {
            activeRun.goal.markFailed(activeRun.exploreTargetBiome != null
                    ? "target biome not reached after all attempts"
                    : "verified inventory has " + current + "/" + activeRun.requested
                            + " after all attempts");
            run = null;
        }
    }


    private static String rawName(net.minecraft.core.BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** Goal completion against live state, for items and exploration. */
    private boolean isSatisfied(ActiveRun activeRun, int current) {
        if (activeRun.exploreTargetBiome != null) {
            return activeRun.exploreTargetBiome.equals(activeRun.biomeAt.current());
        }
        if (activeRun.structureTargetPos != null) {
            return activeRun.structureDistance.getAsDouble()
                    <= STRUCTURE_ARRIVE_DISTANCE_SQ;
        }
        return current >= activeRun.requested;
    }

    private boolean runQueueIsEmpty(ActiveRun activeRun) {
        return activeRun.queue.isEmpty();
    }

    /** Plans from scratch and fills the run's action queue. */
    private boolean replan(ActiveRun activeRun) {
        // Reset pipeline flags first; planFor re-secures food if needed.
        activeRun.recovering = false;
        activeRun.securingFood = false;
        List<AgentAction> actions = planFor(activeRun);
        if (actions.isEmpty()) {
            return false;
        }
        activeRun.attempts++;
        activeRun.queue.clear();
        activeRun.queue.addAll(actions);
        return launchNextAction();
    }

    private boolean launchNextAction() {
        ActiveRun activeRun = run;
        if (activeRun == null || activeRun.queue.isEmpty()) {
            return false;
        }
        return executor.launch(activeRun.queue.poll());
    }

    private List<AgentAction> planFor(ActiveRun activeRun) {
        ServerPlayer player = activeRun.server.getPlayerList().getPlayer(activeRun.playerId);
        if (player == null) {
            return List.of();
        }
        if (activeRun.exploreTargetBiome != null) {
            // M9: one open-ended explore step; verification is live biome.
            net.minecraft.core.BlockPos center = player.blockPosition();
            McAgent.LOGGER.info("[Planner] Plan generated: [Explore to {}]",
                    activeRun.exploreTargetBiome.replaceFirst("^minecraft:", ""));
            return List.of(new com.bhautik.mcagent.action.ExploreAction(
                    activeRun.exploreTargetBiome, center.getX(), center.getZ(),
                    activeRun.biomeAt::current, executor.baritoneIntegration()));
        }
        if (activeRun.structureTargetPos != null) {
            // M9 structures: walk to the located position; arrival is
            // verified against live distance by the goal check.
            var target = activeRun.structureTargetPos;
            McAgent.LOGGER.info("[Planner] Plan generated: [Travel to {}]", rawName(target));
            return List.of(new com.bhautik.mcagent.action.MoveAction(
                    activeRun.goal.title().replace("Explore to ", ""),
                    target.getX(), target.getY(), target.getZ(),
                    Math.sqrt(STRUCTURE_ARRIVE_DISTANCE_SQ),
                    () -> activeRun.structureDistance.getAsDouble(),
                    executor.baritoneIntegration()));
        }
        int current = activeRun.snapshot.count(activeRun.item);
        activeRun.snapshot.setCount(activeRun.item, current);
        try {
            var countsNow = InventoryState.collect(player).itemCounts();
            var environment = environmentFor(activeRun, player, countsNow);
            List<AgentAction> actions = executor.planner().planAcquisition(
                    new VanillaRecipeResolver(activeRun.server,
                            com.bhautik.mcagent.crafting.RecipeResolver.Grid.INVENTORY_2X2),
                    id -> countsNow.getOrDefault(id, 0),
                    countsNow.keySet(),
                    itemId -> liveCountById(activeRun.server, activeRun.playerId, itemId),
                    environment,
                    activeRun.item.id(),
                    activeRun.requested);
            if (activeRun.needsEmergencyFood) {
                List<AgentAction> forage = forageEmergencyFood(environment);
                if (!forage.isEmpty()) {
                    // Food first; the interruption system stands down
                    // (securingFood) until the recovery step consumes it.
                    actions.addAll(0, forage);
                    actions.add(new RecoverAction(activeRun.survivalMonitor,
                            VanillaSurvivalMonitor.feeder(player)));
                    activeRun.securingFood = true;
                }
                activeRun.needsEmergencyFood = false;
            }
            if (actions.isEmpty()) {
                activeRun.goal.markFailed(
                        "no supported acquisition strategy for " + activeRun.item.id());
            } else {
                McAgent.LOGGER.info("[Planner] Plan generated: {}",
                        actions.stream().map(AgentAction::title).toList());
            }
            return actions;
        } catch (com.bhautik.mcagent.planner.Planner.PlanningException planningFailure) {
            activeRun.goal.markFailed(planningFailure.getMessage());
            return List.of();
        }
    }

    /** Builds the full execution seam bundle against live world state. */
    private com.bhautik.mcagent.planner.Planner.Environment environmentFor(
            ActiveRun activeRun, ServerPlayer player,
            Map<String, Integer> countsNow) {
        return new com.bhautik.mcagent.planner.Planner.Environment(
                VanillaCraftingExecutor.forPlayer(player, activeRun.server),
                com.bhautik.mcagent.crafting.VanillaSmelter.forPlayer(player,
                        com.bhautik.mcagent.integration.VanillaPlacementExecutor.INTERACTION_RADIUS),
                com.bhautik.mcagent.integration.VanillaPlacementExecutor.placer(player),
                com.bhautik.mcagent.integration.VanillaPlacementExecutor.breaker(player,
                        com.bhautik.mcagent.integration.VanillaPlacementExecutor.INTERACTION_RADIUS),
                new VanillaRecipeResolver(activeRun.server,
                        com.bhautik.mcagent.crafting.RecipeResolver.Grid.INVENTORY_2X2),
                new com.bhautik.mcagent.crafting.VanillaSmeltingResolver(activeRun.server),
                com.bhautik.mcagent.integration.VanillaPlacementExecutor.blockLocator(player,
                        com.bhautik.mcagent.planner.Planner.CRAFTING_TABLE_ITEM,
                        com.bhautik.mcagent.integration.VanillaPlacementExecutor.INTERACTION_RADIUS),
                com.bhautik.mcagent.integration.VanillaPlacementExecutor.blockLocator(player,
                        com.bhautik.mcagent.planner.Planner.FURNACE_ITEM,
                        com.bhautik.mcagent.integration.VanillaPlacementExecutor.INTERACTION_RADIUS),
                (x, y, z) -> player.distanceToSqr(x, y, z),
                com.bhautik.mcagent.integration.VanillaPlacementExecutor.tunnelLighter(player,
                        com.bhautik.mcagent.planner.Planner.TORCH_ITEM),
                () -> player.level().getBiome(player.blockPosition()).unwrapKey()
                        .map(key -> key.identifier().toString()).orElse(""),
                new com.bhautik.mcagent.world.PositionAnchor() {
                    @Override public int x() { return player.blockPosition().getX(); }
                    @Override public int z() { return player.blockPosition().getZ(); }
                });
    }

    /**
     * Plans gathering the first resolvable emergency food (berries,
     * mushrooms). Empty when none of the candidates can be resolved.
     */
    private List<AgentAction> forageEmergencyFood(
            com.bhautik.mcagent.planner.Planner.Environment environment) {
        ActiveRun activeRun = run;
        var countsNow = InventoryState.collect(
                activeRun.server.getPlayerList().getPlayer(activeRun.playerId)).itemCounts();
        for (String foodId : EMERGENCY_FOODS) {
            try {
                List<AgentAction> plan = executor.planner().planAcquisition(
                        new VanillaRecipeResolver(activeRun.server,
                                com.bhautik.mcagent.crafting.RecipeResolver.Grid.INVENTORY_2X2),
                        id -> countsNow.getOrDefault(id, 0),
                        countsNow.keySet(),
                        itemId -> liveCountById(activeRun.server, activeRun.playerId, itemId),
                        environment, foodId, EMERGENCY_FOOD_COUNT);
                if (!plan.isEmpty()) {
                    McAgent.LOGGER.info("[Planner] Emergency food plan: {}",
                            plan.stream().map(AgentAction::title).toList());
                    return plan;
                }
            } catch (com.bhautik.mcagent.planner.Planner.PlanningException ignored) {
                // try the next candidate food
            }
        }
        return List.of();
    }

    private String finishWithFailure(ActiveRun activeRun) {
        activeRun.goal.markFailed(executor.baritoneIntegration().available()
                ? "planning produced no executable actions"
                : "no navigation backend available");
        McAgent.LOGGER.warn("[Agent] Goal failed: {} ({})",
                activeRun.goal.title(), activeRun.goal.failureReason());
        run = null;
        return activeRun.goal.progressReport();
    }

    private void abandonRun(String reason) {
        ActiveRun activeRun = run;
        if (activeRun == null) {
            return;
        }
        executor.cancelCurrent(reason);
        activeRun.goal.markFailed(reason);
        McAgent.LOGGER.warn("[Agent] Goal abandoned: {} ({})", activeRun.goal.title(), reason);
        run = null;
    }

    private void refreshSnapshot(ServerPlayer player) {
        ActiveRun activeRun = run;
        if (activeRun == null || activeRun.item == null) {
            return; // exploration runs track biomes, not item counts
        }
        activeRun.snapshot.setCount(activeRun.item, liveCount(player, activeRun));
    }

    private static int liveCount(ServerPlayer player, ActiveRun activeRun) {
        return liveCountById(player.level().getServer(), player.getUUID(), activeRun.item.id());
    }

    private static int liveCountById(MinecraftServer server, UUID playerId, String itemId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return 0;
        }
        return InventoryState.collect(player).itemCounts().getOrDefault(itemId, 0);
    }

    private static int liveCount(MinecraftServer server, UUID playerId, MinecraftItem item) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return 0;
        }
        return InventoryState.collect(player).itemCounts().getOrDefault(item.id(), 0);
    }

    private static dev.minecraftai.agent.world.InventoryState snapshot(ServerPlayer player) {
        dev.minecraftai.agent.world.InventoryState snapshot = new dev.minecraftai.agent.world.InventoryState();
        InventoryState.collect(player).itemCounts()
                .forEach((itemId, count) -> snapshot.setCount(new MinecraftItem(itemId), count));
        return snapshot;
    }

    private static final class ActiveRun {
        final AgentGoal goal;
        final Deque<AgentAction> queue = new ArrayDeque<>();
        final MinecraftItem item;
        final UUID playerId;
        final int requested;
        final dev.minecraftai.agent.world.InventoryState snapshot;
        final MinecraftServer server;
        final com.bhautik.mcagent.survival.SurvivalMonitor survivalMonitor;
        int attempts;
        long tickCount;
        /** True while a survival recovery step is in the pipeline. */
        boolean recovering;
        /** Set when a recovery starved; next plan gathers food first. */
        boolean needsEmergencyFood;
        /** True while the queue works through gathered emergency food. */
        boolean securingFood;
        /** Non-null for exploration runs (M9): the qualified biome id. */
        String exploreTargetBiome;
        /** Live biome under the agent, wired only for exploration runs. */
        com.bhautik.mcagent.world.BiomeSensor biomeAt;
        /** Non-null for structure runs (M9): the located block position. */
        net.minecraft.core.BlockPos structureTargetPos;
        /** Live distance to the structure target, wired with biomeAt. */
        java.util.function.DoubleSupplier structureDistance;

        ActiveRun(AgentGoal goal, MinecraftItem item, UUID playerId, int requested,
                  dev.minecraftai.agent.world.InventoryState snapshot, MinecraftServer server,
                  com.bhautik.mcagent.survival.SurvivalMonitor survivalMonitor) {
            this.goal = goal;
            this.item = item;
            this.playerId = playerId;
            this.requested = requested;
            this.snapshot = snapshot;
            this.server = server;
            this.survivalMonitor = survivalMonitor;
        }
    }
}
