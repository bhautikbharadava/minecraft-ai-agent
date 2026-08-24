package com.bhautik.mcagent.planner;

import com.bhautik.mcagent.action.AgentAction;
import com.bhautik.mcagent.action.CraftAction;
import com.bhautik.mcagent.action.MineAction;
import com.bhautik.mcagent.action.PlaceBlockAction;
import com.bhautik.mcagent.crafting.RecipeResolver;
import com.bhautik.mcagent.integration.BaritoneIntegration;
import com.bhautik.mcagent.item.DirectAcquisitions;
import com.bhautik.mcagent.world.TableLocator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Expands a resource gap into an ordered action list by resolving
 * dependencies recursively: mine what is directly acquirable, craft the
 * rest from real vanilla recipes, always crediting current inventory.
 *
 * Emitted plans are post-order: dependencies appear before dependents,
 * which lets the single-action runner execute them sequentially.
 *
 * Recipes wider or taller than the inventory grid insert crafting-table
 * steps: acquire a table item, place it once per plan (skipped entirely
 * when a table is already within range), then craft against it.
 */
public final class Planner {
    /** The one block item the agent currently knows how to place. */
    public static final String CRAFTING_TABLE_ITEM = "minecraft:crafting_table";

    /**
     * Execution seams handed to emitted actions: real grid crafting,
     * real block placement, and the world check that verifies both.
     */
    public record Environment(CraftAction.Crafter crafter,
                              PlaceBlockAction.Placer placer,
                              TableLocator tableLocator) {
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
     * @param environment   execution seams for crafting and placement
     */
    public List<AgentAction> planAcquisition(RecipeResolver resolver,
                                             Function<String, Integer> plannedCounts,
                                             Set<String> ownedItemIds,
                                             ToIntFunction<String> liveCounts,
                                             Environment environment,
                                             String itemId,
                                             int targetCount) {
        Expansion expansion = new Expansion(resolver, plannedCounts, ownedItemIds,
                liveCounts, environment);
        expansion.expand(itemId, targetCount);
        return expansion.plan;
    }

    /** Per-plan state: output list, cycle guard, and table dedup flag. */
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
        private boolean tableHandled;

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
                String toolReason = DirectAcquisitions.missingToolReason(
                        itemId, itemsOwnedOrPlanned());
                if (toolReason != null) {
                    planMissingTool(itemId, toolReason);
                }
                plan.add(new MineAction(sourceBlock, have, have + missing,
                        () -> liveCounts.applyAsInt(itemId), baritoneIntegration));
                produced.merge(itemId, missing, Integer::sum);
                return;
            }
            if (!visiting.add(itemId)) {
                throw new PlanningException("circular dependency on " + itemId);
            }
            RecipeResolver.CraftableRecipe recipe = resolver.findRecipe(itemId)
                    .orElseThrow(() -> new PlanningException(
                            "no supported acquisition strategy for " + itemId));
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
            if (recipe.requiresTable() && !environment.tableLocator().isNearby()) {
                // The world has no table yet: get one into inventory and
                // place it exactly once per plan, no matter how many
                // table-gated crafts follow.
                expand(CRAFTING_TABLE_ITEM, 1);
                if (!tableHandled) {
                    plan.add(new PlaceBlockAction(CRAFTING_TABLE_ITEM,
                            environment.placer(), environment.tableLocator()));
                    tableHandled = true;
                }
            }
            visiting.remove(itemId);
            BooleanSupplier tableGate = recipe.requiresTable()
                    ? environment.tableLocator()::isNearby
                    : null;
            plan.add(new CraftAction(recipe, crafts, () -> liveCounts.applyAsInt(itemId),
                    environment.crafter(), tableGate));
            produced.merge(itemId, crafts * recipe.resultCount(), Integer::sum);
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
