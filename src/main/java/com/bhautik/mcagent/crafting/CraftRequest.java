package com.bhautik.mcagent.crafting;

import com.bhautik.mcagent.crafting.RecipeResolver.CraftableRecipe;

import java.util.List;

/** A single-item-grid craft to perform a number of times. */
public record CraftRequest(
        CraftableRecipe recipe,
        List<String> chosenItemIds,
        int craftsNeeded,
        String outputItemId
) {
}
