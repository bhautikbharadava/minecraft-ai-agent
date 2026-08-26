package com.bhautik.mcagent.integration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Persistent home-base position for the agent, stored in the overworld's
 * saved data so it survives server restarts.
 */
public final class BaseSavedState extends SavedData {

    private int anchorX;
    private int anchorY;
    private int anchorZ;
    private boolean hasChest;
    private int chestX;
    private int chestY;
    private int chestZ;

    public static final Codec<BaseSavedState> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    Codec.INT.fieldOf("anchor_x").forGetter(s -> s.anchorX),
                    Codec.INT.fieldOf("anchor_y").forGetter(s -> s.anchorY),
                    Codec.INT.fieldOf("anchor_z").forGetter(s -> s.anchorZ),
                    Codec.BOOL.fieldOf("has_chest").forGetter(s -> s.hasChest),
                    Codec.INT.fieldOf("chest_x").forGetter(s -> s.chestX),
                    Codec.INT.fieldOf("chest_y").forGetter(s -> s.chestY),
                    Codec.INT.fieldOf("chest_z").forGetter(s -> s.chestZ)
            ).apply(instance, BaseSavedState::new));

    public static final SavedDataType<BaseSavedState> TYPE = new SavedDataType<>(
            net.minecraft.resources.Identifier.parse("mcagent:base"),
            BaseSavedState::new,
            CODEC,
            null);

    public BaseSavedState() {
    }

    private BaseSavedState(int anchorX, int anchorY, int anchorZ, boolean hasChest,
                           int chestX, int chestY, int chestZ) {
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.hasChest = hasChest;
        this.chestX = chestX;
        this.chestY = chestY;
        this.chestZ = chestZ;
    }

    /** @return the persisted state, creating an empty one when absent. */
    public static BaseSavedState get(net.minecraft.server.MinecraftServer server) {
        var storage = server.overworld().getDataStorage();
        var state = storage.get(TYPE);
        if (state == null) {
            state = new BaseSavedState();
            storage.set(TYPE, state);
        }
        return state;
    }

    public void setAnchor(int x, int y, int z) {
        this.anchorX = x;
        this.anchorY = y;
        this.anchorZ = z;
        setDirty();
    }

    public int[] anchor() {
        return new int[]{anchorX, anchorY, anchorZ};
    }

    public boolean hasChest() {
        return hasChest;
    }

    public void setChest(int x, int y, int z) {
        this.hasChest = true;
        this.chestX = x;
        this.chestY = y;
        this.chestZ = z;
        setDirty();
    }

    public int[] chest() {
        return hasChest ? new int[]{chestX, chestY, chestZ} : null;
    }
}
