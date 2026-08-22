package com.bhautik.mcagent.state;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public record InventoryState(Map<String, Integer> itemCounts) {
    public InventoryState {
        itemCounts = Collections.unmodifiableMap(new LinkedHashMap<>(itemCounts));
    }

    public static InventoryState collect(ServerPlayerEntity player) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (!stack.isEmpty()) {
                String itemId = Registries.ITEM.getId(stack.getItem()).toString();
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
