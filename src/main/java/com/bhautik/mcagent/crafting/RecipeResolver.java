package com.bhautik.mcagent.crafting;

import java.util.List;
import java.util.Optional;

/**
 * Framework-free view over vanilla crafting data so the planner can
 * resolve dependency trees without touching Minecraft classes.
 *
 * Only inventory-grid (2x2) recipes are exposed; anything larger needs a
 * crafting table and arrives in a later milestone.
 */
public interface RecipeResolver {

    /** @return the largest grid the agent can craft in right now. */
    Grid grid();

    Optional<CraftableRecipe> findRecipe(String outputItemId);

    /**
     * One crafting-grid cell. {@code candidateItemIds} is the accepted
     * item set; an empty list marks an intentionally empty cell
     * (shaped-recipe padding).
     */
    record SlotSpec(List<String> candidateItemIds) {
        public static final SlotSpec EMPTY = new SlotSpec(List.of());

        public boolean isEmpty() {
            return candidateItemIds.isEmpty();
        }
    }

    record CraftableRecipe(
            String resultItemId,
            int resultCount,
            int width,
            int height,
            boolean requiresTable,
            List<SlotSpec> cells
    ) {
        public CraftableRecipe {
            if (cells.size() != width * height) {
                throw new IllegalArgumentException("cells must be width*height");
            }
            cells = List.copyOf(cells);
        }

        /** Non-empty ingredient cells, in placement order. */
        public List<SlotSpec> occupiedSlots() {
            return cells.stream().filter(cell -> !cell.isEmpty()).toList();
        }
    }

    enum Grid {
        INVENTORY_2X2,
        CRAFTING_TABLE_3X3
    }
}
