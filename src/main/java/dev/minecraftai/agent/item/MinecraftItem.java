package dev.minecraftai.agent.item;

import java.util.Objects;

public final class MinecraftItem {
    private final String id;

    public MinecraftItem(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
