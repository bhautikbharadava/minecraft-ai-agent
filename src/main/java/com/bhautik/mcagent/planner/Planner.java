package com.bhautik.mcagent.planner;

import com.bhautik.mcagent.action.AgentAction;
import com.bhautik.mcagent.action.MineAction;
import com.bhautik.mcagent.integration.BaritoneIntegration;
import com.bhautik.mcagent.item.MineableItems;

import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;

/**
 * Turns a resource gap into executable actions. Always accounts for the
 * inventory baseline before planning new acquisition.
 */
public final class Planner {
    private final BaritoneIntegration baritoneIntegration;

    public Planner(BaritoneIntegration baritoneIntegration) {
        this.baritoneIntegration = baritoneIntegration;
    }

    /**
     * Plans how to close the gap between {@code baselineCount} and
     * {@code targetCount} of an item. Returns an empty list when the item
     * has no supported acquisition strategy.
     */
    public List<AgentAction> planAcquisition(String itemId, int baselineCount, int targetCount,
                                             IntSupplier liveCount) {
        int missing = targetCount - baselineCount;
        if (missing <= 0) {
            return List.of();
        }
        Optional<String> sourceBlock = MineableItems.sourceBlockFor(itemId);
        if (sourceBlock.isEmpty()) {
            return List.of();
        }
        return List.of(new MineAction(sourceBlock.get(), baselineCount, targetCount, liveCount,
                baritoneIntegration));
    }
}
