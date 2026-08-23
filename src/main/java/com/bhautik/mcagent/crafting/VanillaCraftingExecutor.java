package com.bhautik.mcagent.crafting;

import com.bhautik.mcagent.action.CraftAction;

import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Performs real inventory-grid crafting against a player's inventory,
 * matching recipes through the server's recipe manager.
 */
public final class VanillaCraftingExecutor {

    private VanillaCraftingExecutor() {
    }

    public static CraftAction.Crafter forPlayer(ServerPlayer player, MinecraftServer server) {
        return (recipe, times) -> {
            int completed = 0;
            for (int attempt = 0; attempt < times; attempt++) {
                if (!craftOnce(player, server, recipe)) {
                    break;
                }
                completed++;
            }
            return completed;
        };
    }

    private static boolean craftOnce(ServerPlayer player, MinecraftServer server,
                                     RecipeResolver.CraftableRecipe recipe) {
        List<ItemStack> placed = new ArrayList<>();
        for (RecipeResolver.SlotSpec cell : recipe.cells()) {
            placed.add(cell.isEmpty() ? ItemStack.EMPTY : findStack(player, cell));
        }
        if (placed.stream().anyMatch(java.util.Objects::isNull)) {
            return false; // ingredient vanished mid-run
        }
        CraftingInput input = CraftingInput.of(recipe.width(), recipe.height(),
                new ArrayList<>(placed));
        Level level = player.level();
        RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe> holder =
                server.getRecipeManager()
                        .getRecipeFor(RecipeType.CRAFTING, input, level)
                        .orElse(null);
        if (holder == null) {
            return false;
        }
        // Consume one item per occupied cell, then grant the result.
        for (ItemStack stack : placed) {
            if (!stack.isEmpty()) {
                consumeOne(player, stack);
            }
        }
        ItemStack result = holder.value().assemble(input);
        if (!result.isEmpty()) {
            if (!player.getInventory().add(result)) {
                player.drop(result, false);
            }
        }
        return true;
    }

    /** Peeks the first inventory stack accepted by this grid cell. */
    private static ItemStack findStack(ServerPlayer player, RecipeResolver.SlotSpec cell) {
        Container inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (cell.candidateItemIds().contains(itemId)) {
                return stack.copyWithCount(1);
            }
        }
        return null;
    }

    private static void consumeOne(ServerPlayer player, ItemStack template) {
        String itemId = BuiltInRegistries.ITEM.getKey(template.getItem()).toString();
        Container inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String candidateId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (candidateId.equals(itemId)) {
                stack.shrink(1);
                return;
            }
        }
    }
}
