package com.bhautik.mcagent.crafting;

import java.util.List;
import java.util.Optional;

/**
 * Framework-free view over vanilla smelting data so the planner can
 * route ores and raw materials through furnaces without touching
 * Minecraft classes.
 */
public interface SmeltingResolver {

    Optional<SmeltableRecipe> findSmelting(String outputItemId);

    /**
     * @param candidateInputItemIds accepted inputs, representative first
     */
    record SmeltableRecipe(String outputItemId, List<String> candidateInputItemIds) {
        public SmeltableRecipe {
            if (candidateInputItemIds.isEmpty()) {
                throw new IllegalArgumentException("smelting needs at least one input");
            }
            candidateInputItemIds = List.copyOf(candidateInputItemIds);
        }

        public String representativeInput() {
            return candidateInputItemIds.get(0);
        }
    }
}
