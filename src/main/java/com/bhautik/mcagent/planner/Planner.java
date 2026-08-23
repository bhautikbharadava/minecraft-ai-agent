package com.bhautik.mcagent.planner;

import com.bhautik.mcagent.action.AgentAction;
import com.bhautik.mcagent.action.CraftAction;
import com.bhautik.mcagent.action.MineAction;
import com.bhautik.mcagent.crafting.RecipeResolver;
import com.bhautik.mcagent.integration.BaritoneIntegration;
import com.bhautik.mcagent.item.DirectAcquisitions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Expands a resource gap into an ordered action list by resolving
 * dependencies recursively: mine what is directly acquirable, craft the
 * rest from real vanilla recipes, always crediting current inventory.
 *
 * Emitted plans are post-order: dependencies appear before dependents,
 * which lets the single-action runner execute them sequentially.
 */
public final class Planner {
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
     * @param crafter       performs real grid crafting during execution
     */
    public List<AgentAction> planAcquisition(RecipeResolver resolver,
                                             Function<String, Integer> plannedCounts,
                                             Set<String> ownedItemIds,
                                             ToIntFunction<String> liveCounts,
                                             CraftAction.Crafter crafter,
                                             String itemId,
                                             int targetCount) {
        List<AgentAction> plan = new ArrayList<>();
        expand(resolver, plannedCounts, ownedItemIds, liveCounts, crafter,
                itemId, targetCount, plan, new HashSet<>());
        return plan;
    }

    private void expand(RecipeResolver resolver, Function<String, Integer> plannedCounts,
                        Set<String> ownedItemIds, ToIntFunction<String> liveCounts,
                        CraftAction.Crafter crafter, String itemId, int needed,
                        List<AgentAction> out, Set<String> visiting) {
        int have = Math.max(plannedCounts.apply(itemId), 0);
        int missing = needed - have;
        if (missing <= 0) {
            return; // already satisfied; PRD: credit existing inventory first
        }
        String sourceBlock = DirectAcquisitions.sourceBlockFor(itemId).orElse(null);
        if (sourceBlock != null) {
            String toolReason = DirectAcquisitions.missingToolReason(itemId, ownedItemIds);
            if (toolReason != null) {
                throw new PlanningException(toolReason);
            }
            out.add(new MineAction(sourceBlock, have, have + missing,
                    () -> liveCounts.applyAsInt(itemId), baritoneIntegration));
            return;
        }
        if (!visiting.add(itemId)) {
            throw new PlanningException("circular dependency on " + itemId);
        }
        RecipeResolver.CraftableRecipe recipe = resolver.findRecipe(itemId)
                .orElseThrow(() -> new PlanningException(
                        "no supported acquisition strategy for " + itemId));
        if (recipe.requiresTable()) {
            throw new PlanningException(itemId.replaceFirst("^minecraft:", "")
                    + " requires a crafting table (3x3), which the agent cannot use yet");
        }
        int crafts = ceilDiv(missing, recipe.resultCount());
        // Aggregate identical ingredient slots first (UC-09: shared
        // dependencies are deduplicated, e.g. 4 plank slots -> one demand).
        Map<String, Integer> demands = new java.util.LinkedHashMap<>();
        for (RecipeResolver.SlotSpec cell : recipe.cells()) {
            if (cell.isEmpty()) {
                continue;
            }
            String representative = cell.candidateItemIds().get(0);
            demands.merge(representative, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> demand : demands.entrySet()) {
            expand(resolver, plannedCounts, ownedItemIds, liveCounts, crafter,
                    demand.getKey(), demand.getValue() * crafts, out, visiting);
        }
        visiting.remove(itemId);
        out.add(new CraftAction(recipe, crafts, () -> liveCounts.applyAsInt(itemId), crafter));
    }

    private static int ceilDiv(int dividend, int divisor) {
        return (dividend + divisor - 1) / divisor;
    }
}
