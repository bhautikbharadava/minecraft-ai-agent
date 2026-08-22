package dev.minecraftai.agent.world;

import dev.minecraftai.agent.item.MinecraftItem;

import java.util.HashMap;
import java.util.Map;

public final class InventoryState {
    private final Map<String, Integer> itemCounts = new HashMap<>();

    public int count(MinecraftItem item) {
        return itemCounts.getOrDefault(item.id(), 0);
    }

    public void setCount(MinecraftItem item, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Inventory counts cannot be negative.");
        }
        itemCounts.put(item.id(), count);
    }
}
