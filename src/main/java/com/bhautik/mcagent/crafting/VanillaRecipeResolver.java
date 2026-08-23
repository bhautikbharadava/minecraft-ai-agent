package com.bhautik.mcagent.crafting;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Indexes vanilla crafting recipes into the framework-free planner model.
 * Built lazily per server instance; recipes only change on datapack reload.
 */
public final class VanillaRecipeResolver implements RecipeResolver {
    private final MinecraftServer server;
    private final Grid grid;
    private volatile Map<String, CraftableRecipe> index;

    public VanillaRecipeResolver(MinecraftServer server, Grid grid) {
        this.server = server;
        this.grid = grid;
    }

    @Override
    public Grid grid() {
        return grid;
    }

    @Override
    public Optional<CraftableRecipe> findRecipe(String outputItemId) {
        return Optional.ofNullable(index().get(outputItemId));
    }

    private Map<String, CraftableRecipe> index() {
        Map<String, CraftableRecipe> local = index;
        if (local == null) {
            synchronized (this) {
                if (index == null) {
                    index = build();
                }
                local = index;
            }
        }
        return local;
    }

    private Map<String, CraftableRecipe> build() {
        int maxSide = grid == Grid.CRAFTING_TABLE_3X3 ? 3 : 2;
        RecipeManager manager = server.getRecipeManager();
        Map<String, CraftableRecipe> built = new HashMap<>();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (!(holder.value() instanceof CraftingRecipe recipe) || recipe.isSpecial()) {
                continue;
            }
            GridDefinition definition = gridOf(recipe, maxSide);
            if (definition == null) {
                continue;
            }
            ItemStack result = resultOf(recipe);
            if (result.isEmpty()) {
                continue;
            }
            List<SlotSpec> cells = new ArrayList<>();
            boolean resolvable = true;
            for (Ingredient ingredient : definition.cells()) {
                if (ingredient == null || ingredient.isEmpty()) {
                    cells.add(SlotSpec.EMPTY);
                    continue;
                }
                List<String> candidates = ingredient.items()
                        .map(item -> BuiltInRegistries.ITEM.getKey(item.value()).toString())
                        .toList();
                if (candidates.isEmpty()) {
                    resolvable = false;
                    break;
                }
                cells.add(new SlotSpec(candidates));
            }
            // Keep the simplest recipe per output (fewest occupied slots).
            if (!resolvable || cells.isEmpty() || cells.stream().allMatch(SlotSpec::isEmpty)) {
                continue;
            }
            CraftableRecipe candidate = new CraftableRecipe(
                    BuiltInRegistries.ITEM.getKey(result.getItem()).toString(),
                    Math.max(1, result.getCount()),
                    definition.width(), definition.height(), cells);
            built.merge(candidate.resultItemId(), candidate,
                    (existing, proposed) -> proposed.occupiedSlots().size() < existing.occupiedSlots().size()
                            ? proposed : existing);
        }
        return built;
    }

    private record GridDefinition(int width, int height, List<Ingredient> cells) {
    }

    private static GridDefinition gridOf(CraftingRecipe recipe, int maxSide) {
        if (recipe instanceof ShapedRecipe shaped) {
            int width = shaped.getWidth();
            int height = shaped.getHeight();
            if (width > maxSide || height > maxSide) {
                return null;
            }
            // 26.x has no Ingredient.EMPTY and air ingredients throw on
            // construction, so shaped padding stays null until it becomes
            // an explicit SlotSpec.EMPTY cell.
            return new GridDefinition(width, height,
                    shaped.getIngredients().stream()
                            .map(optional -> optional.orElse(null))
                            .toList());
        }
        if (recipe instanceof ShapelessRecipe shapeless) {
            List<Ingredient> ingredients = shapeless.placementInfo().ingredients();
            if (ingredients.isEmpty() || ingredients.size() > maxSide) {
                return null;
            }
            return new GridDefinition(ingredients.size(), 1, ingredients);
        }
        return null; // custom matcher recipes are not planned this milestone
    }

    private static ItemStack resultOf(CraftingRecipe recipe) {
        try {
            ItemStack assembled = recipe.assemble(CraftingInput.EMPTY);
            return assembled == null ? ItemStack.EMPTY : assembled;
        } catch (Throwable failedToSynthesize) {
            return ItemStack.EMPTY;
        }
    }
}
