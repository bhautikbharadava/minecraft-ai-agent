package com.bhautik.mcagent.planner;

import com.bhautik.mcagent.action.AgentAction;
import com.bhautik.mcagent.action.BreakBlockAction;
import com.bhautik.mcagent.action.CraftAction;
import com.bhautik.mcagent.action.Equipper;
import com.bhautik.mcagent.action.MineAction;
import com.bhautik.mcagent.action.MoveAction;
import com.bhautik.mcagent.action.PlaceBlockAction;
import com.bhautik.mcagent.action.SmeltAction;
import com.bhautik.mcagent.crafting.RecipeResolver;
import com.bhautik.mcagent.crafting.SmeltingResolver;
import com.bhautik.mcagent.integration.BaritoneIntegration;
import com.bhautik.mcagent.item.DirectAcquisitions;
import com.bhautik.mcagent.world.BiomeSensor;
import com.bhautik.mcagent.world.BlockLocator;
import com.bhautik.mcagent.world.DistanceSensor;
import com.bhautik.mcagent.world.PositionAnchor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Expands a resource gap into an ordered action list by resolving
 * dependencies recursively, always crediting current inventory:
 * mine what is directly acquirable, then smelt what has a furnace
 * recipe, then craft the rest from real vanilla recipes.
 *
 * Smelting is preferred over crafting for smeltable outputs because
 * their only crafting routes are closed loops (nuggets <-> ingots);
 * ores enter the plan through mining instead.
 *
 * Emitted plans are post-order: dependencies appear before dependents,
 * which lets the single-action runner execute them sequentially.
 *
 * Recipes wider or taller than the inventory grid insert crafting-table
 * steps; furnace recipes insert furnace steps. Utility blocks are walked
 * to when one exists nearby, otherwise acquired, placed once per plan,
 * and collected again at the end.
 */
public final class Planner {
    /** The block item the agent uses for 3x3 crafting. */
    public static final String CRAFTING_TABLE_ITEM = "minecraft:crafting_table";
    /** The block item the agent uses for smelting. */
    public static final String FURNACE_ITEM = "minecraft:furnace";
    /** Standard fuel the planner demands for furnace runs. */
    public static final String FUEL_ITEM = "minecraft:coal";
    /** Lighting carried for tunnel safety (mob-spawn prevention). */
    public static final String TORCH_ITEM = "minecraft:torch";
    /** Storage block of the home base. */
    public static final String BASE_CHEST_ITEM = "minecraft:chest";
    /** Mining plans trigger a torch top-up when stock drops below this. */
    public static final int MIN_TORCHES = 16;
    /** Food the agent keeps pre-stocked before long goals. */
    public static final String FOOD_UPKEEP_ITEM = "minecraft:sweet_berries";
    /** Trigger when fewer than this many edibles are carried. */
    public static final int MIN_EDIBLES = 6;
    /** Upkeep crafts/forages this much before the goal. */
    public static final int FOOD_STACK_TARGET = 12;
    /** Top-ups fill a full stack so long digs never run dark. */
    public static final int TORCH_STACK_TARGET = 64;

    /** How far the agent will walk to reach an existing utility block. */
    public static final int BLOCK_SEARCH_RADIUS = 48;

    /** Blocks (squared) considered close enough to use a utility block. */
    public static final double BLOCK_ARRIVE_DISTANCE_SQ = 9.0;

    /**
     * Execution seams handed to emitted actions: real grid crafting,
     * real furnace cooking, real block placement and collection, world
     * checks that verify all of it, continuous tunnel lighting, and the
     * live biome/anchor used to chain exploration for gated resources.
     */
    public record Environment(CraftAction.Crafter crafter,
                              SmeltAction.Smelter smelter,
                              PlaceBlockAction.Placer placer,
                              BreakBlockAction.Breaker breaker,
                              RecipeResolver resolver,
                              SmeltingResolver smeltingResolver,
                              BlockLocator tableLocator,
                              BlockLocator furnaceLocator,
                              DistanceSensor distanceSensor,
                              BiomeSensor biomeSensor,
                              PositionAnchor anchor,
                              Equipper equipper) {
    }

    private final BaritoneIntegration baritoneIntegration;

    public Planner(BaritoneIntegration baritoneIntegration) {
        this.baritoneIntegration = baritoneIntegration;
    }

    /** Planning refused because the goal is impossible right now. */
    public static final class PlanningException extends RuntimeException {
        public PlanningException(String message) {
            super(message);
        }
    }

    /**
     * @param plannedCounts inventory counts captured at plan time (credit
     *                      what the player already owns)
     * @param ownedItemIds  inventory item ids used for tool gating
     * @param liveCounts    live counts per item id, for action verification
     * @param environment   execution seams for crafting, smelting, placement
     */
    public List<AgentAction> planAcquisition(RecipeResolver resolver,
                                             Function<String, Integer> plannedCounts,
                                             Set<String> ownedItemIds,
                                             ToIntFunction<String> liveCounts,
                                             Environment environment,
                                             String itemId,
                                             int targetCount) {
        return planAcquisition(resolver, plannedCounts, ownedItemIds, liveCounts,
                environment, List.of(Map.entry(itemId, targetCount)));
    }

    /**
     * Multi-root planning (PRD UC-09): all roots share one Expansion, so
     * a full armor set mines its total diamond need once instead of four
     * independent ladders re-gathering the same intermediates.
     */
    public List<AgentAction> planAcquisition(RecipeResolver resolver,
                                             Function<String, Integer> plannedCounts,
                                             Set<String> ownedItemIds,
                                             ToIntFunction<String> liveCounts,
                                             Environment environment,
                                             List<Map.Entry<String, Integer>> roots) {
        Expansion expansion = new Expansion(resolver, plannedCounts, ownedItemIds,
                liveCounts, environment);
        for (Map.Entry<String, Integer> root : roots) {
            expansion.expand(root.getKey(), root.getValue());
        }
        List<AgentAction> plan = new ArrayList<>(expansion.plan);
        // Torch upkeep (PRD 15 background tier): mining plans top torches
        // back up first, when the recipe resolves and stock is low.
        boolean minesSomething = plan.stream().anyMatch(step -> step instanceof MineAction);
        int carriedTorches = Math.max(plannedCounts.apply(TORCH_ITEM), 0);
        if (minesSomething && carriedTorches < MIN_TORCHES) {
            Expansion torchRun = new Expansion(resolver, plannedCounts, ownedItemIds,
                    liveCounts, environment);
            try {
                torchRun.expand(TORCH_ITEM, TORCH_STACK_TARGET - carriedTorches);
                plan.addAll(0, torchRun.plan);
                com.bhautik.mcagent.McAgent.LOGGER.info(
                        "[Planner] Torch upkeep prepended (plan now {} steps)", plan.size());
            } catch (PlanningException unresolvableTorches) {
                // No torch route: mine anyway; survival handles the rest.
            }
        }
        // Food upkeep: keep edibles stocked before mining-heavy goals, so
        // starvation interrupts stay rare. Mirrors torch upkeep.
        int carriedEdibles = Math.max(plannedCounts.apply(FOOD_UPKEEP_ITEM), 0);
        boolean goalIsFood = roots.stream().anyMatch(e -> FOOD_UPKEEP_ITEM.equals(e.getKey()));
        if (!goalIsFood && minesSomething && carriedEdibles < MIN_EDIBLES) {
            Expansion foodRun = new Expansion(resolver, plannedCounts, ownedItemIds,
                    liveCounts, environment);
            try {
                foodRun.expand(FOOD_UPKEEP_ITEM, FOOD_STACK_TARGET - carriedEdibles);
                plan.addAll(0, foodRun.plan);
                com.bhautik.mcagent.McAgent.LOGGER.info(
                        "[Planner] Food upkeep prepended (plan now {} steps)", plan.size());
            } catch (PlanningException unresolvableFood) {
                // No food route: proceed; survival handles the rest reactively.
            }
        }
        // A table or furnace this plan placed is picked back up once
        // everything else ran; pre-existing world blocks stay put.
        for (String placed : expansion.placedBlocks) {
            BlockLocator locator = placed.equals(CRAFTING_TABLE_ITEM)
                    ? environment.tableLocator() : environment.furnaceLocator();
            plan.add(new BreakBlockAction(placed,
                    environment.breaker(),
                    () -> liveCounts.applyAsInt(placed)));
        }
        return plan;
    }

    /** Per-plan state: output list, cycle guard, and block bookkeeping. */
    private final class Expansion {
        private final RecipeResolver resolver;
        private final Function<String, Integer> plannedCounts;
        private final Set<String> ownedItemIds;
        private final ToIntFunction<String> liveCounts;
        private final Environment environment;
        private final List<AgentAction> plan = new ArrayList<>();
        private final Set<String> visiting = new HashSet<>();
        /** Output already promised by earlier steps of this same plan, so
         * sibling branches never re-gather shared intermediates. */
        private final Map<String, Integer> produced = new HashMap<>();
        /** Items whose tool gate is currently being satisfied up-tree,
         * guarding against circular tool requirements. */
        private final Set<String> toolChains = new HashSet<>();
        /** Blocks this plan itself placed, in order (dedups placement). */
        private final Set<String> placedBlocks = new LinkedHashSet<>();

        private Expansion(RecipeResolver resolver, Function<String, Integer> plannedCounts,
                          Set<String> ownedItemIds, ToIntFunction<String> liveCounts,
                          Environment environment) {
            this.resolver = resolver;
            this.plannedCounts = plannedCounts;
            this.ownedItemIds = ownedItemIds;
            this.liveCounts = liveCounts;
            this.environment = environment;
        }

        private void expand(String itemId, int needed) {
            int have = Math.max(plannedCounts.apply(itemId), 0)
                    + produced.getOrDefault(itemId, 0);
            int missing = needed - have;
            if (missing <= 0) {
                return; // already satisfied; PRD: credit existing inventory first
            }
            String sourceBlock = DirectAcquisitions.sourceBlockFor(itemId).orElse(null);
            if (sourceBlock != null) {
                Set<String> ownedOrPlanned = itemsOwnedOrPlanned();
                String toolReason = DirectAcquisitions.missingToolReason(itemId, ownedOrPlanned);
                if (toolReason != null) {
                    planMissingTool(itemId, toolReason);
                }
                // Biome-locked sources require standing in their biome
                // first; exploration is just another dependency step.
                DirectAcquisitions.requiredBiomeFor(itemId).ifPresent(required -> {
                    if (!required.equals(environment.biomeSensor().current())) {
                        plan.add(new com.bhautik.mcagent.action.ExploreAction(required,
                                environment.anchor().x(), environment.anchor().z(),
                                environment.biomeSensor()::current, baritoneIntegration));
                    }
                });
                // Hold the right tier while mining: wrong-tool breaks
                // destroy gated ores without dropping anything.
                String toolToHold = DirectAcquisitions
                        .qualifyingToolFor(itemId, itemsOwnedOrPlanned())
                        .orElse(null);
                plan.add(new MineAction(sourceBlock, have, have + missing,
                        () -> liveCounts.applyAsInt(itemId), baritoneIntegration,
                        toolToHold,
                        environment.anchor(),
                        environment.equipper()));
                produced.merge(itemId, missing, Integer::sum);
                return;
            }
            if (!visiting.add(itemId)) {
                throw new PlanningException("circular dependency on " + itemId);
            }
            var smeltable = environment.smeltingResolver().findSmelting(itemId).orElse(null);
            if (smeltable != null) {
                expandSmelting(itemId, missing, smeltable);
                visiting.remove(itemId);
                produced.merge(itemId, missing, Integer::sum);
                return;
            }
            RecipeResolver.CraftableRecipe recipe = resolver.findRecipe(itemId)
                    .orElseThrow(() -> new PlanningException(
                            "no supported acquisition strategy for " + itemId));
            expandCrafting(itemId, missing, recipe);
            visiting.remove(itemId);
            produced.merge(itemId, ceilDiv(missing, recipe.resultCount())
                    * recipe.resultCount(), Integer::sum);
        }

        /** Furnace route: gather input and fuel FIRST, then secure the
         * furnace — placement must happen after every mining step, or
         * Baritone will dig through the freshly placed block on its way
         * to ore veins. */
        private void expandSmelting(String itemId, int missing,
                                    SmeltingResolver.SmeltableRecipe smeltable) {
            String input = pickResolvableInput(smeltable);
            expand(input, missing);
            expand(FUEL_ITEM, ceilDiv(missing, SmeltAction.ITEMS_PER_FUEL));
            planBlockAccess(FURNACE_ITEM, environment.furnaceLocator());
            BooleanSupplier furnaceGate = environment.furnaceLocator()::isNearby;
            plan.add(new SmeltAction(input, FUEL_ITEM, missing,
                    () -> liveCounts.applyAsInt(itemId),
                    environment.smelter(), furnaceGate));
        }

        private String pickResolvableInput(SmeltingResolver.SmeltableRecipe smeltable) {
            for (String candidate : smeltable.candidateInputItemIds()) {
                if (DirectAcquisitions.sourceBlockFor(candidate).isPresent()
                        || resolver.findRecipe(candidate).isPresent()
                        || environment.smeltingResolver().findSmelting(candidate).isPresent()) {
                    return candidate;
                }
            }
            return smeltable.representativeInput();
        }

        /** Grid route: aggregate ingredient slots, secure a table, craft. */
        private void expandCrafting(String itemId, int missing,
                                    RecipeResolver.CraftableRecipe recipe) {
            int crafts = ceilDiv(missing, recipe.resultCount());
            // Aggregate identical ingredient slots first (UC-09: shared
            // dependencies are deduplicated, e.g. 4 plank slots -> one demand).
            Map<String, Integer> demands = new LinkedHashMap<>();
            for (RecipeResolver.SlotSpec cell : recipe.cells()) {
                if (cell.isEmpty()) {
                    continue;
                }
                String representative = cell.candidateItemIds().get(0);
                demands.merge(representative, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> demand : demands.entrySet()) {
                expand(demand.getKey(), demand.getValue() * crafts);
            }
            if (recipe.requiresTable()) {
                planBlockAccess(CRAFTING_TABLE_ITEM, environment.tableLocator());
            }
            BooleanSupplier tableGate = recipe.requiresTable()
                    ? environment.tableLocator()::isNearby
                    : null;
            plan.add(new CraftAction(recipe, crafts, () -> liveCounts.applyAsInt(itemId),
                    environment.crafter(), tableGate));
        }

        /**
         * Guarantees the plan reaches a utility block before any gated
         * step: walk to an existing one when possible, otherwise acquire
         * the item and place it — at most one placement per block per
         * plan. Walks may repeat; arrival checks make repeats free.
         */
        private void planBlockAccess(String blockItemId, BlockLocator locator) {
            if (locator.isNearby()) {
                return;
            }
            locator.nearestWithin(BLOCK_SEARCH_RADIUS).ifPresentOrElse(
                    site -> {
                        // Walk to a spot BESIDE the block: targeting the
                        // block itself makes navigation mine it on arrival.
                        BlockLocator.BlockSite approach =
                                locator.approachFor(site).orElse(site);
                        plan.add(new MoveAction(blockItemId,
                                approach.x(), approach.y(), approach.z(),
                                BLOCK_ARRIVE_DISTANCE_SQ,
                                () -> environment.distanceSensor()
                                        .distanceSquaredTo(approach.x(), approach.y(),
                                                approach.z()),
                                baritoneIntegration));
                    },
                    () -> {
                        expand(blockItemId, 1);
                        if (placedBlocks.add(blockItemId)) {
                            plan.add(new PlaceBlockAction(blockItemId,
                                    environment.placer(), locator));
                        }
                    });
        }

        /**
         * A gated block needs a tool the agent neither owns nor plans to
         * own: fold the cheapest qualifying tool's full dependency chain
         * into this plan (craft it before mining), or refuse honestly.
         */
        private void planMissingTool(String itemId, String toolReason) {
            String tool = DirectAcquisitions.simplestToolFor(itemId)
                    .orElseThrow(() -> new PlanningException(toolReason));
            if (!toolChains.add(itemId)) {
                throw new PlanningException("circular tool requirement for " + itemId);
            }
            try {
                expand(tool, 1);
            } catch (PlanningException unobtainableTool) {
                throw new PlanningException(shortName(itemId) + " " + toolReason
                        + "; cannot plan a " + shortName(tool) + ": "
                        + unobtainableTool.getMessage());
            } finally {
                toolChains.remove(itemId);
            }
            String afterPlanning = DirectAcquisitions.missingToolReason(
                    itemId, itemsOwnedOrPlanned());
            if (afterPlanning != null) {
                throw new PlanningException(afterPlanning);
            }
        }

        /** Inventory ids plus everything earlier steps of this plan produce. */
        private Set<String> itemsOwnedOrPlanned() {
            Set<String> ownedOrPlanned = new HashSet<>(ownedItemIds);
            ownedOrPlanned.addAll(produced.keySet());
            return ownedOrPlanned;
        }

        private static String shortName(String itemId) {
            return itemId.replaceFirst("^minecraft:", "");
        }

        private static int ceilDiv(int dividend, int divisor) {
            return (dividend + divisor - 1) / divisor;
        }
    }
}
