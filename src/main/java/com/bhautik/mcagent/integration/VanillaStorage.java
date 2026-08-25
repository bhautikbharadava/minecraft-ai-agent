package com.bhautik.mcagent.integration;

import com.bhautik.mcagent.action.DepositAction;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

/**
 * Storage seam: moves matching stacks from the agent's inventory into a
 * nearby chest (the base chest), merging where possible. Server-side
 * and verified by the caller through moved-stack counts.
 */
public final class VanillaStorage {

    /** Hotbar + main rows the agent uses for loot. */
    public static final int MAIN_INVENTORY_SIZE = 36;

    private VanillaStorage() {
    }

    public static DepositAction.Depositor depositor(ServerPlayer player, int radius) {
        return (itemIds, maxStacks) -> {
            Container chest = findChest(player, radius);
            if (chest == null) {
                return 0;
            }
            int moved = 0;
            var inventory = player.getInventory();
            for (int slot = inventory.getContainerSize() - 1;
                    slot >= 0 && moved < maxStacks; slot--) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty() || !itemIds.contains(idOf(stack))) {
                    continue;
                }
                if (tryStore(chest, stack)) {
                    moved++;
                    inventory.removeItem(slot, stack.getCount());
                }
            }
            return moved;
        };
    }

    /** Merges into matching chest stacks first, then any empty slot. */
    private static boolean tryStore(Container chest, ItemStack stack) {
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack target = chest.getItem(slot);
            if (!target.isEmpty()
                    && ItemStack.isSameItemSameComponents(stack, target)
                    && target.getCount() < target.getMaxStackSize()) {
                int space = target.getMaxStackSize() - target.getCount();
                int amount = Math.min(space, stack.getCount());
                target.grow(amount);
                stack.shrink(amount);
                if (stack.isEmpty()) {
                    return true;
                }
            }
        }
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            if (chest.getItem(slot).isEmpty()) {
                chest.setItem(slot, stack.copy());
                stack.setCount(0);
                return true;
            }
        }
        return false;
    }

    private static Container findChest(ServerPlayer player, int radius) {
        BlockPos origin = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-radius, -radius, -radius),
                origin.offset(radius, radius, radius))) {
            var state = player.level().getBlockState(pos);
            if (state.is(Blocks.CHEST)
                    && player.level().getBlockEntity(pos)
                            instanceof RandomizableContainerBlockEntity container) {
                return container;
            }
        }
        return null;
    }

    private static String idOf(ItemStack stack) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
    }
}
