package com.bhautik.mcagent.integration;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.build.Blueprint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads vanilla {@code .nbt} structure files into {@link Blueprint}s.
 *
 * <p>That format is chosen because it needs no third-party parser and
 * because a player can author designs in-game with structure blocks and
 * drop the file in - no hand-written coordinates.
 *
 * <p>The palette is parsed directly rather than going through
 * {@code StructureTemplate}, because the agent has to know the material
 * list BEFORE building in order to gather it.
 */
public final class NbtBlueprintLoader {

    /** Where structure files are read from, under the game directory. */
    public static final String BLUEPRINT_DIR = "config/mcagent/blueprints";

    private NbtBlueprintLoader() {
    }

    /** Names of every structure file available on disk. */
    public static Set<String> available() {
        Path dir = Path.of(BLUEPRINT_DIR);
        if (!Files.isDirectory(dir)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".nbt"))
                    .map(name -> name.substring(0, name.length() - 4))
                    .collect(java.util.stream.Collectors.toSet());
        } catch (Exception unreadable) {
            McAgent.LOGGER.warn("[Build] Could not list blueprints: {}",
                    String.valueOf(unreadable));
            return Set.of();
        }
    }

    public static Optional<Blueprint> load(String name) {
        Path file = Path.of(BLUEPRINT_DIR, name + ".nbt");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            return Optional.of(parse(name, root));
        } catch (Exception failed) {
            McAgent.LOGGER.warn("[Build] Could not read blueprint {}: {}", name,
                    String.valueOf(failed));
            return Optional.empty();
        }
    }

    /**
     * Structure NBT layout: "size" is three ints, "palette" is a list of
     * block states, and "blocks" pairs a palette index with a position.
     */
    private static Blueprint parse(String name, CompoundTag root) {
        int[] size = readSize(root);
        List<String> palette = new ArrayList<>();
        for (Tag entry : listOf(root, "palette")) {
            if (entry instanceof CompoundTag state) {
                palette.add(state.getString("Name").orElse(Blueprint.AIR));
            }
        }
        List<Blueprint.Placement> placements = new ArrayList<>();
        for (Tag entry : listOf(root, "blocks")) {
            if (!(entry instanceof CompoundTag block)) {
                continue;
            }
            int stateIndex = block.getInt("state").orElse(-1);
            if (stateIndex < 0 || stateIndex >= palette.size()) {
                continue;
            }
            int[] pos = readIntTriple(block, "pos");
            if (pos == null) {
                continue;
            }
            placements.add(new Blueprint.Placement(pos[0], pos[1], pos[2],
                    palette.get(stateIndex)));
        }
        return new Blueprint(name, size[0], size[1], size[2], placements);
    }

    private static int[] readSize(CompoundTag root) {
        int[] size = readIntTriple(root, "size");
        return size == null ? new int[]{0, 0, 0} : size;
    }

    private static int[] readIntTriple(CompoundTag tag, String key) {
        ListTag values = tag.getList(key).orElse(null);
        if (values == null || values.size() < 3) {
            return null;
        }
        return new int[]{
                values.getInt(0).orElse(0),
                values.getInt(1).orElse(0),
                values.getInt(2).orElse(0)};
    }

    private static ListTag listOf(CompoundTag tag, String key) {
        return tag.getList(key).orElseGet(ListTag::new);
    }
}
