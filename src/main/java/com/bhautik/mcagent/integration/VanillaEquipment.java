package com.bhautik.mcagent.integration;

import com.bhautik.mcagent.action.Equipper;
import com.bhautik.mcagent.item.DirectAcquisitions;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Server-side tool selection. The integrated server owns the drop
 * calculation, so swapping must happen on its inventory copy; vanilla
 * then syncs slot changes and the carried item to the client. Mutating
 * only the client copy silently desyncs both sides.
 */
public final class VanillaEquipment {

    private static final int HOTBAR_SIZE = 9;

    private VanillaEquipment() {
    }

    public static Equipper equipper(ServerPlayer player) {
        return itemId -> {
            var inventory = player.getInventory();
            int selected = inventory.getSelectedSlot();
            if (idOf(inventory.getItem(selected)).equals(itemId)) {
                return true; // already in hand
            }
            // A sufficient pickaxe tier is as good as the exact tool:
            // forcing a swap here fights Baritone's auto-tool and resets
            // block-break progress mid-dig.
            if (DirectAcquisitions.pickaxeTierAtLeast(
                    idOf(inventory.getItem(selected)), itemId)) {
                return true;
            }
            Integer hotbarSlot = findSlot(inventory, itemId, 0, HOTBAR_SIZE);
            if (hotbarSlot != null) {
                inventory.setSelectedSlot(hotbarSlot);
                return true;
            }
            Integer backpackSlot = findSlot(inventory, itemId,
                    HOTBAR_SIZE, inventory.getContainerSize());
            if (backpackSlot == null) {
                return false; // not carried at all
            }
            Integer destination = freeHotbarSlot(inventory);
            if (destination == null) {
                destination = selected; // displace what is held
            }
            ItemStack tool = inventory.getItem(backpackSlot);
            ItemStack displaced = inventory.getItem(destination);
            inventory.setItem(destination, tool);
            inventory.setItem(backpackSlot, displaced);
            inventory.setSelectedSlot(destination);
            return true;
        };
    }

    private static Integer findSlot(net.minecraft.world.Container inventory, String itemId,
                                   int from, int to) {
        for (int slot = from; slot < to; slot++) {
            if (idOf(inventory.getItem(slot)).equals(itemId)) {
                return slot;
            }
        }
        return null;
    }

    private static Integer freeHotbarSlot(net.minecraft.world.Container inventory) {
        for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return null;
    }

    private static String idOf(ItemStack stack) {
        return stack.isEmpty() ? ""
                : net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).toString();
    }
}
