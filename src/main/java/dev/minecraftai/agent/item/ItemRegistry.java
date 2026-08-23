package dev.minecraftai.agent.item;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ItemRegistry {
    private final Map<String, MinecraftItem> itemsById;

    public ItemRegistry(Set<String> itemIds) {
        this.itemsById = new HashMap<>();
        for (String itemId : itemIds) {
            MinecraftItem item = new MinecraftItem(normalize(itemId));
            itemsById.put(item.id(), item);
            itemsById.put(item.displayName(), item);
        }
    }

    public static ItemRegistry vanillaDefaults() {
        return new ItemRegistry(Set.of(
                "minecraft:cobblestone",
                "minecraft:dirt",
                "minecraft:oak_log",
                "minecraft:stone",
                "minecraft:iron_ore",
                "minecraft:coal",
                "minecraft:crafting_table"
        ));
    }

    public Optional<MinecraftItem> resolve(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(itemsById.get(normalize(rawName)));
    }

    private static String normalize(String rawName) {
        String normalized = rawName.trim().toLowerCase();
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }
}
