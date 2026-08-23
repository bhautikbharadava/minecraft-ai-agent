package com.bhautik.mcagent.state;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public record InventoryState(Map<String, Integer> itemCounts) {
    public InventoryState {
        itemCounts = Collections.unmodifiableMap(new LinkedHashMap<>(itemCounts));
    }

    public static InventoryState collect(ServerPlayer player) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                counts.merge(itemId, stack.getCount(), Integer::sum);
            }
        }
        return new InventoryState(counts);
    }

    public String summary() {
        if (itemCounts.isEmpty()) {
            return "empty";
        }
        return itemCounts.entrySet().stream()
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .collect(Collectors.joining(", "));
    }
}
