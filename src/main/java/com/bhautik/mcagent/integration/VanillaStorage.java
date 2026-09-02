package com.bhautik.mcagent.integration;

import com.bhautik.mcagent.action.DepositAction;

import net.minecraft.core.BlockPos;
import java.util.Map;
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


    /**
     * Pulls up to {@code maxStacks} stacks of the listed items from a
     * nearby chest into the agent's inventory (restock direction).
     */
    public static com.bhautik.mcagent.action.WithdrawAction.Withdrawer withdrawer(
            ServerPlayer player, int radius) {
        return (itemIds, maxStacks) -> {
            Container chest = findChest(player, radius);
            if (chest == null) {
                return 0;
            }
            int moved = 0;
            for (int slot = 0; slot < chest.getContainerSize() && moved < maxStacks;
                    slot++) {
                ItemStack stack = chest.getItem(slot);
                if (stack.isEmpty() || !itemIds.contains(idOf(stack))) {
                    continue;
                }
                if (player.getInventory().add(stack.copy())) {
                    chest.removeItem(slot, stack.getCount());
                    moved++;
                } else {
                    // Agent bag full: stop before splitting stacks.
                    break;
                }
            }
            return moved;
        };
    }

    /** Ids of edible items currently inside the nearby chest. */
    public static java.util.List<String> foodIdsInChest(ServerPlayer player, int radius) {
        Container chest = findChest(player, radius);
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (chest == null) {
            return ids;
        }
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (!stack.isEmpty()
                    && stack.get(net.minecraft.core.component.DataComponents.FOOD) != null
                    && !ids.contains(idOf(stack))) {
                ids.add(idOf(stack));
            }
        }
        return ids;
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

    /**
     * Every usable storage container within reach: chests plus furnace
     * output inventories (finished smelts waiting to be collected).
     */
    public static java.util.List<Container> nearbyContainers(
            ServerPlayer player, int radius) {
        return containersAround(player.level(), player.blockPosition(), radius);
    }

    /**
     * Containers around an arbitrary position.
     *
     * <p>Needed because base stock has to be readable from anywhere: a
     * player-relative scan only ever saw the base chest while the agent
     * happened to be standing on it, so plans re-gathered materials they
     * already owned.
     */
    public static java.util.List<Container> containersAround(
            net.minecraft.world.level.Level level, BlockPos origin, int radius) {
        java.util.List<Container> containers = new java.util.ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-radius, -radius, -radius),
                origin.offset(radius, radius, radius))) {
            var be = level.getBlockEntity(pos);
            if (be instanceof RandomizableContainerBlockEntity chestContainer) {
                containers.add(chestContainer);
            } else if (be instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace) {
                containers.add(furnace);
            }
        }
        return containers;
    }

    /** Per-item totals across every nearby container. */
    public static Map<String, Integer> storedTotals(ServerPlayer player, int radius) {
        return storedTotalsAround(player.level(), player.blockPosition(), radius);
    }

    /** Per-item totals in containers around a fixed position. */
    public static Map<String, Integer> storedTotalsAround(
            net.minecraft.world.level.Level level, BlockPos origin, int radius) {
        Map<String, Integer> totals = new java.util.HashMap<>();
        for (Container container : containersAround(level, origin, radius)) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) {
                    totals.merge(idOf(stack), stack.getCount(), Integer::sum);
                }
            }
        }
        return totals;
    }

    /**
     * Pulls up to {@code unitCap} items per listed id out of nearby
     * containers (chests plus furnace output slots).
     */
    public static com.bhautik.mcagent.action.WithdrawAction.Withdrawer supplyWithdrawer(
            ServerPlayer player, int radius, Map<String, Integer> unitCapPerId) {
        return (itemIds, maxStacks) -> {
            int moved = 0;
            for (Container container : nearbyContainers(player, radius)) {
                for (int slot = 0; slot < container.getContainerSize()
                        && moved < maxStacks; slot++) {
                    ItemStack stack = container.getItem(slot);
                    String id = idOf(stack);
                    if (stack.isEmpty() || !itemIds.contains(id)) {
                        continue;
                    }
                    int cap = unitCapPerId.getOrDefault(id, Integer.MAX_VALUE);
                    int alreadyInBag = 0;
                    var inv = player.getInventory();
                    for (int bagSlot = 0; bagSlot < inv.getContainerSize(); bagSlot++) {
                        if (idOf(inv.getItem(bagSlot)).equals(id)) {
                            alreadyInBag += inv.getItem(bagSlot).getCount();
                        }
                    }
                    int allowed = Math.min(stack.getCount(), Math.max(0, cap - alreadyInBag));
                    if (allowed <= 0) {
                        continue;
                    }
                    ItemStack take = stack.split(allowed);
                    if (player.getInventory().add(take)) {
                        if (stack.isEmpty()) {
                            container.removeItem(slot, 0);
                            container.setItem(slot, ItemStack.EMPTY);
                        } else {
                            container.setItem(slot, stack);
                        }
                        moved++;
                    } else {
                        stack.grow(take.getCount()); // bag full: give back
                    }
                }
            }
            return moved;
        };
    }

    /** Position of the nearest storage container (any chest/barrel kind). */
    public static BlockPos findStoragePos(ServerPlayer player, int radius) {
        BlockPos origin = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-radius, -radius, -radius),
                origin.offset(radius, radius, radius))) {
            var be = player.level().getBlockEntity(pos);
            if (be instanceof RandomizableContainerBlockEntity) {
                return pos.immutable();
            }
        }
        return null;
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
