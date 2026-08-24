package com.bhautik.mcagent.goal;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.action.AgentAction;
import com.bhautik.mcagent.crafting.VanillaCraftingExecutor;
import com.bhautik.mcagent.crafting.VanillaRecipeResolver;
import com.bhautik.mcagent.executor.AgentExecutor;
import com.bhautik.mcagent.state.InventoryState;
import dev.minecraftai.agent.goal.AgentGoalManager;
import dev.minecraftai.agent.goal.GetItemGoal;
import dev.minecraftai.agent.goal.GoalStatus;
import dev.minecraftai.agent.item.ItemRegistry;
import dev.minecraftai.agent.item.MinecraftItem;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
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
    private static final int MAX_PLAN_ATTEMPTS = 2;

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
                    snapshot, player.level().getServer());
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
            executor.tick();

            run.tickCount++;
            if (run.tickCount % PROGRESS_REFRESH_INTERVAL_TICKS == 0) {
                refreshSnapshot(player);
            }

            executor.pollFinished().ifPresent(finished -> handleFinishedAction(player, finished));
        }
    }

    private void handleFinishedAction(ServerPlayer player, AgentAction finished) {
        ActiveRun activeRun = run;
        if (activeRun == null) {
            return;
        }
        refreshSnapshot(player);
        int current = activeRun.snapshot.count(activeRun.item);
        switch (finished.status()) {
            case CANCELLED -> {
                run = null;
            }
            case FAILED -> {
                McAgent.LOGGER.warn("[Recovery] Action failed: {} ({})",
                        finished.title(), finished.failureReason());
                if (activeRun.attempts < MAX_PLAN_ATTEMPTS && replan(activeRun)) {
                    McAgent.LOGGER.info("[Recovery] Retrying with a fresh plan");
                } else {
                    finishWithFailure(activeRun);
                }
            }
            default -> {
                // Step finished; advance the plan or verify against reality.
                if (current >= activeRun.requested) {
                    activeRun.goal.markSuccess();
                    McAgent.LOGGER.info("[Agent] Goal completed: {}",
                            activeRun.goal.title());
                    run = null;
                } else if (!activeRun.queue.isEmpty() && launchNextAction()) {
                    McAgent.LOGGER.info("[Planner] Advancing plan: {}", executor.currentTitle());
                } else if (activeRun.attempts < MAX_PLAN_ATTEMPTS && replan(activeRun)) {
                    McAgent.LOGGER.info("[Recovery] Retrying with a fresh plan");
                } else {
                    activeRun.goal.markFailed(
                            "verified inventory has " + current + "/" + activeRun.requested
                                    + " after all attempts");
                    run = null;
                }
            }
        }
    }

    /** Plans from scratch and fills the run's action queue. */
    private boolean replan(ActiveRun activeRun) {
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
        int current = activeRun.snapshot.count(activeRun.item);
        activeRun.snapshot.setCount(activeRun.item, current);
        try {
            var countsNow = InventoryState.collect(player).itemCounts();
            var environment = new com.bhautik.mcagent.planner.Planner.Environment(
                    VanillaCraftingExecutor.forPlayer(player, activeRun.server),
                    com.bhautik.mcagent.integration.VanillaPlacementExecutor.placer(player),
                    com.bhautik.mcagent.integration.VanillaPlacementExecutor.tableLocator(player,
                            com.bhautik.mcagent.integration.VanillaPlacementExecutor.INTERACTION_RADIUS),
                    (x, y, z) -> player.distanceToSqr(x, y, z));
            List<AgentAction> actions = executor.planner().planAcquisition(
                    new VanillaRecipeResolver(activeRun.server,
                            com.bhautik.mcagent.crafting.RecipeResolver.Grid.INVENTORY_2X2),
                    id -> countsNow.getOrDefault(id, 0),
                    countsNow.keySet(),
                    itemId -> liveCountById(activeRun.server, activeRun.playerId, itemId),
                    environment,
                    activeRun.item.id(),
                    activeRun.requested);
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
        if (activeRun == null) {
            return;
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
        final GetItemGoal goal;
        final Deque<AgentAction> queue = new ArrayDeque<>();
        final MinecraftItem item;
        final UUID playerId;
        final int requested;
        final dev.minecraftai.agent.world.InventoryState snapshot;
        final MinecraftServer server;
        int attempts;
        long tickCount;

        ActiveRun(GetItemGoal goal, MinecraftItem item, UUID playerId, int requested,
                  dev.minecraftai.agent.world.InventoryState snapshot, MinecraftServer server) {
            this.goal = goal;
            this.item = item;
            this.playerId = playerId;
            this.requested = requested;
            this.snapshot = snapshot;
            this.server = server;
        }
    }
}
