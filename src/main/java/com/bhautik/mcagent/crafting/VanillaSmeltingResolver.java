package com.bhautik.mcagent.crafting;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Indexes vanilla furnace recipes into the framework-free planner model.
 * Built lazily per server instance; recipes only change on datapack reload.
 */
public final class VanillaSmeltingResolver implements SmeltingResolver {
    private final MinecraftServer server;
    private volatile Map<String, SmeltableRecipe> index;

    public VanillaSmeltingResolver(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public Optional<SmeltableRecipe> findSmelting(String outputItemId) {
        return Optional.ofNullable(index().get(outputItemId));
    }

    private Map<String, SmeltableRecipe> index() {
        Map<String, SmeltableRecipe> local = index;
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

    private Map<String, SmeltableRecipe> build() {
        RecipeManager manager = server.getRecipeManager();
        Map<String, SmeltableRecipe> built = new HashMap<>();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (!(holder.value() instanceof AbstractCookingRecipe recipe)
                    || recipe.getType() != RecipeType.SMELTING
                    || recipe.isSpecial()) {
                continue;
            }
            ItemStack result = resultOf(recipe);
            if (result.isEmpty()) {
                continue;
            }
            String outputId =
                    BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
            List<String> candidates = recipe.input().items()
                    .map(item -> BuiltInRegistries.ITEM.getKey(item.value()).toString())
                    .toList();
            if (candidates.isEmpty()) {
                continue;
            }
            SmeltableRecipe candidate = new SmeltableRecipe(outputId, candidates);
            // Union accepted inputs across duplicate-output recipes
            // (vanilla registers raw ore, ore, and deepslate ore variants
            // separately); the planner picks whichever it can gather.
            built.merge(outputId, candidate, (existing, proposed) -> {
                java.util.LinkedHashSet<String> union =
                        new java.util.LinkedHashSet<>(existing.candidateInputItemIds());
                union.addAll(proposed.candidateInputItemIds());
                return new SmeltableRecipe(outputId, List.copyOf(union));
            });
        }
        return built;
    }

    private static ItemStack resultOf(AbstractCookingRecipe recipe) {
        try {
            ItemStack assembled =
                    recipe.assemble(new SingleRecipeInput(ItemStack.EMPTY));
            return assembled == null ? ItemStack.EMPTY : assembled;
        } catch (Throwable failedToSynthesize) {
            return ItemStack.EMPTY;
        }
    }
}
