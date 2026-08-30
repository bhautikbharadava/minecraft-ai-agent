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
    private boolean hasEnchantTable;
    private int enchantX;
    private int enchantY;
    private int enchantZ;
    private java.util.List<com.bhautik.mcagent.build.Reservation> reservations =
            new java.util.ArrayList<>();
    private boolean hasFarm;
    private int farmX;
    private int farmY;
    private int farmZ;

    /** Ground already committed to a structure, so builds do not collide. */
    private static final Codec<com.bhautik.mcagent.build.Reservation> RESERVATION_CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.STRING.fieldOf("name")
                            .forGetter(com.bhautik.mcagent.build.Reservation::name),
                    Codec.INT.fieldOf("x").forGetter(com.bhautik.mcagent.build.Reservation::x),
                    Codec.INT.fieldOf("y").forGetter(com.bhautik.mcagent.build.Reservation::y),
                    Codec.INT.fieldOf("z").forGetter(com.bhautik.mcagent.build.Reservation::z),
                    Codec.INT.fieldOf("w").forGetter(com.bhautik.mcagent.build.Reservation::width),
                    Codec.INT.fieldOf("h").forGetter(com.bhautik.mcagent.build.Reservation::height),
                    Codec.INT.fieldOf("l").forGetter(com.bhautik.mcagent.build.Reservation::length)
            ).apply(instance, com.bhautik.mcagent.build.Reservation::new));

    // The enchanting-table fields are optional so bases saved before the
    // enchanting milestone still load instead of dropping the whole base.
    public static final Codec<BaseSavedState> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    Codec.INT.fieldOf("anchor_x").forGetter(s -> s.anchorX),
                    Codec.INT.fieldOf("anchor_y").forGetter(s -> s.anchorY),
                    Codec.INT.fieldOf("anchor_z").forGetter(s -> s.anchorZ),
                    Codec.BOOL.fieldOf("has_chest").forGetter(s -> s.hasChest),
                    Codec.INT.fieldOf("chest_x").forGetter(s -> s.chestX),
                    Codec.INT.fieldOf("chest_y").forGetter(s -> s.chestY),
                    Codec.INT.fieldOf("chest_z").forGetter(s -> s.chestZ),
                    Codec.BOOL.optionalFieldOf("has_enchant_table", false)
                            .forGetter(s -> s.hasEnchantTable),
                    Codec.INT.optionalFieldOf("enchant_x", 0).forGetter(s -> s.enchantX),
                    Codec.INT.optionalFieldOf("enchant_y", 0).forGetter(s -> s.enchantY),
                    Codec.INT.optionalFieldOf("enchant_z", 0).forGetter(s -> s.enchantZ),
                    Codec.BOOL.optionalFieldOf("has_farm", false)
                            .forGetter(s -> s.hasFarm),
                    Codec.INT.optionalFieldOf("farm_x", 0).forGetter(s -> s.farmX),
                    Codec.INT.optionalFieldOf("farm_y", 0).forGetter(s -> s.farmY),
                    Codec.INT.optionalFieldOf("farm_z", 0).forGetter(s -> s.farmZ),
                    RESERVATION_CODEC.listOf().optionalFieldOf("reservations",
                            java.util.List.of()).forGetter(s -> s.reservations)
            ).apply(instance, BaseSavedState::new));


    public static final SavedDataType<BaseSavedState> TYPE = new SavedDataType<>(
            net.minecraft.resources.Identifier.parse("mcagent:base"),
            BaseSavedState::new,
            CODEC,
            null);

    public BaseSavedState() {
    }

    private BaseSavedState(int anchorX, int anchorY, int anchorZ, boolean hasChest,
                           int chestX, int chestY, int chestZ,
                           boolean hasEnchantTable, int enchantX, int enchantY,
                           int enchantZ, boolean hasFarm, int farmX, int farmY,
                           int farmZ,
                           java.util.List<com.bhautik.mcagent.build.Reservation> reservations) {
        this.reservations = new java.util.ArrayList<>(reservations);
        this.hasFarm = hasFarm;
        this.farmX = farmX;
        this.farmY = farmY;
        this.farmZ = farmZ;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.hasChest = hasChest;
        this.chestX = chestX;
        this.chestY = chestY;
        this.chestZ = chestZ;
        this.hasEnchantTable = hasEnchantTable;
        this.enchantX = enchantX;
        this.enchantY = enchantY;
        this.enchantZ = enchantZ;
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

    public boolean hasEnchantTable() {
        return hasEnchantTable;
    }

    /** Records the permanent enchanting table built at the base. */
    public void setEnchantTable(int x, int y, int z) {
        this.hasEnchantTable = true;
        this.enchantX = x;
        this.enchantY = y;
        this.enchantZ = z;
        setDirty();
    }

    public int[] enchantTable() {
        return hasEnchantTable ? new int[]{enchantX, enchantY, enchantZ} : null;
    }

    public boolean hasFarm() {
        return hasFarm;
    }

    /** Records the crop plot tilled at the base, so harvests return to it. */
    public void setFarm(int x, int y, int z) {
        this.hasFarm = true;
        this.farmX = x;
        this.farmY = y;
        this.farmZ = z;
        setDirty();
    }

    public int[] farm() {
        return hasFarm ? new int[]{farmX, farmY, farmZ} : null;
    }

    /** Every patch of ground already committed to a structure. */
    public java.util.List<com.bhautik.mcagent.build.Reservation> reservations() {
        return java.util.List.copyOf(reservations);
    }

    /** Claims ground for a structure so later builds are placed clear of it. */
    public void reserve(com.bhautik.mcagent.build.Reservation reservation) {
        reservations.add(reservation);
        setDirty();
    }
}
