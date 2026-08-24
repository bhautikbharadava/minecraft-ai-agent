package com.bhautik.mcagent.crafting;

import com.bhautik.mcagent.action.SmeltAction;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;

/**
 * Minecraft adapter behind the smelting seam: loads input and fuel into
 * the nearest furnace's real block entity (vanilla logic then cooks on
 * its own), and harvests finished output back into player inventory.
 */
public final class VanillaSmelter {

    /** Vanilla furnace container layout. */
    private static final int SLOT_INPUT = 0;
    private static final int SLOT_FUEL = 1;

    private VanillaSmelter() {
    }

    public static SmeltAction.Smelter forPlayer(ServerPlayer player, int radius) {
        return new SmeltAction.Smelter() {
            @Override
            public SmeltAction.Smelter.Result begin(String inputItemId, int inputCount,
                                                    String fuelItemId, int fuelCount) {
                FurnaceBlockEntity furnace = findFurnace(player, radius);
                if (furnace == null) {
                    return SmeltAction.Smelter.Result.failed("no furnace within reach");
                }
                if (!load(player, furnace, SLOT_INPUT, inputItemId, inputCount)) {
                    return SmeltAction.Smelter.Result.failed(
                            "could not load " + shortName(inputItemId) + " into furnace");
                }
                if (!load(player, furnace, SLOT_FUEL, fuelItemId, fuelCount)) {
                    return SmeltAction.Smelter.Result.failed(
                            "could not load " + shortName(fuelItemId) + " as fuel");
                }
                return SmeltAction.Smelter.Result.ok();
            }

            @Override
            public SmeltAction.Smelter.Result harvest() {
                FurnaceBlockEntity furnace = findFurnace(player, radius);
                if (furnace == null) {
                    return SmeltAction.Smelter.Result.failed("no furnace within reach");
                }
                ItemStack output = furnace.getItem(2);
                if (!output.isEmpty()) {
                    ItemStack harvested = output.copy();
                    if (!player.getInventory().add(harvested)) {
                        player.drop(harvested, false);
                    }
                    furnace.removeItem(2, output.getCount());
                }
                return SmeltAction.Smelter.Result.ok();
            }
        };
    }

    /**
     * Moves {@code count} of an item from player inventory into a furnace
     * slot, merging with whatever is already there when compatible.
     */
    private static boolean load(ServerPlayer player, FurnaceBlockEntity furnace,
                                int slot, String itemId, int count) {
        Container inventory = furnace;
        ItemStack current = inventory.getItem(slot);
        if (!current.isEmpty() && !idOf(current).equals(itemId)) {
            return false; // slot holds another item
        }
        int capacity = current.isEmpty()
                ? Math.min(inventory.getMaxStackSize(), 64)
                : Math.min(inventory.getMaxStackSize(), current.getMaxStackSize())
                        - current.getCount();
        if (capacity < count) {
            return false; // slot cannot take the whole batch
        }
        ItemStack pulled = pullFromPlayer(player, itemId, count);
        if (pulled == null) {
            return false; // agent is not carrying enough
        }
        if (current.isEmpty()) {
            inventory.setItem(slot, pulled);
        } else {
            current.grow(pulled.getCount());
            inventory.setItem(slot, current);
        }
        return true;
    }

    /** Removes exactly {@code count} of an item from player inventory, or null. */
    private static ItemStack pullFromPlayer(ServerPlayer player, String itemId, int count) {
        int remaining = count;
        ItemStack first = null;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !idOf(stack).equals(itemId)) {
                continue;
            }
            int taken = Math.min(remaining, stack.getCount());
            ItemStack part = stack.split(taken);
            if (first == null) {
                first = part;
            } else {
                first.grow(part.getCount());
            }
            remaining -= taken;
        }
        return remaining > 0 ? null : first;
    }

    private static FurnaceBlockEntity findFurnace(ServerPlayer player, int radius) {
        BlockPos origin = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-radius, -radius, -radius),
                origin.offset(radius, radius, radius))) {
            if (player.level().getBlockState(pos).is(Blocks.FURNACE)
                    && player.level().getBlockEntity(pos) instanceof FurnaceBlockEntity furnace) {
                return furnace;
            }
        }
        return null;
    }

    private static ItemStack findStack(ServerPlayer player, String itemId) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && idOf(stack).equals(itemId)) {
                return stack;
            }
        }
        return null;
    }

    private static String idOf(ItemStack stack) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
    }

    private static String shortName(String itemId) {
        return itemId.replaceFirst("^minecraft:", "");
    }
}
