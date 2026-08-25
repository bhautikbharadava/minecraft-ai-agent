package com.bhautik.mcagent.world;

import java.util.Map;
import java.util.Optional;

/**
 * Names the agent can search for, mapped to vanilla structure tags
 * (data/minecraft/tags/worldgen/structure). Framework-free: ids only.
 */
public final class StructureDirectory {

    /** Friendly name -> vanilla structure tag id (namespaced on use). */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("village", "village"),
            Map.entry("mineshaft", "mineshaft"),
            Map.entry("shipwreck", "shipwreck"),
            Map.entry("ruined_portal", "ruined_portal"),
            Map.entry("buried_treasure", "buried_treasure"),
            Map.entry("stronghold", "eye_of_ender_located")
    );

    private StructureDirectory() {
    }

    /** Resolves a friendly structure name to its vanilla tag id. */
    public static Optional<String> tagFor(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(ALIASES.get(rawName.trim().toLowerCase()));
    }

    public static boolean isSearchable(String rawName) {
        return tagFor(rawName).isPresent();
    }
}
