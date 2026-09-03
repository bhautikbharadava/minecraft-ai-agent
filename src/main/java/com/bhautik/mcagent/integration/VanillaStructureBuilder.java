package com.bhautik.mcagent.integration;

import com.bhautik.mcagent.action.StructureBuilder;
import com.bhautik.mcagent.world.BlockLocator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * Places blueprint blocks in the live world. Server-authoritative like
 * the rest of this codebase's mutation: the integrated server's state is
 * set and vanilla syncs the client.
 *
 * <p>Materials are consumed for real, so a build the agent cannot pay
 * for fails instead of conjuring blocks. Water is the exception vanilla
 * itself makes - it comes from a bucket, which is refilled rather than
 * consumed.
 */
public final class VanillaStructureBuilder {

    private static final String WATER = "minecraft:water";
    private static final String FARMLAND = "minecraft:farmland";

    private VanillaStructureBuilder() {
    }

    public static StructureBuilder builder(ServerPlayer player) {
        return new StructureBuilder() {
            @Override
            public boolean place(BlockLocator.BlockSite at, String blockId) {
                var level = player.level();
                var pos = new BlockPos(at.x(), at.y(), at.z());
                if (idOf(level.getBlockState(pos).getBlock()).equals(blockId)) {
                    return true; // already right
                }
                var block = blockFor(blockId);
                if (block == null) {
                    return false;
                }
                if (FARMLAND.equals(blockId)) {
                    // Tilled from soil that is already there, never
                    // fabricated: a hoe converts dirt, it does not create
                    // it. Breaking-then-setting turned stone and air into
                    // farmland out of nothing.
                    var existing = level.getBlockState(pos);
                    boolean soil = existing.is(Blocks.DIRT)
                            || existing.is(Blocks.GRASS_BLOCK)
                            || existing.is(Blocks.COARSE_DIRT)
                            || existing.is(Blocks.ROOTED_DIRT);
                    if (!soil || !hasHoe(player)) {
                        return false;
                    }
                    level.setBlock(pos, Blocks.FARMLAND.defaultBlockState(), 3);
                    return level.getBlockState(pos).is(Blocks.FARMLAND);
                }
                if (WATER.equals(blockId)) {
                    // A water source needs a filled bucket, exactly as a
                    // player would need one; the bucket comes back empty.
                    if (!spendWaterBucket(player)) {
                        return false;
                    }
                    level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
                    return level.getBlockState(pos).is(Blocks.WATER);
                }
                if (!spendBlockItem(player, blockId)) {
                    return false;
                }
                // Break what is there first so it DROPS. setBlock straight
                // over an existing block silently deletes it, which is how
                // a build once ate a base chest and everything in it.
                if (!level.getBlockState(pos).isAir()) {
                    level.destroyBlock(pos, true, player);
                }
                level.setBlock(pos, block.defaultBlockState(), 3);
                return idOf(level.getBlockState(pos).getBlock()).equals(blockId);
            }

            @Override
            public boolean clear(BlockLocator.BlockSite at) {
                var level = player.level();
                var pos = new BlockPos(at.x(), at.y(), at.z());
                if (level.getBlockState(pos).isAir()) {
                    return true;
                }
                return level.destroyBlock(pos, true, player);
            }

            @Override
            public String blockAt(BlockLocator.BlockSite at) {
                return idOf(player.level()
                        .getBlockState(new BlockPos(at.x(), at.y(), at.z()))
                        .getBlock());
            }

            @Override
            public boolean isProtected(BlockLocator.BlockSite at) {
                var level = player.level();
                var pos = new BlockPos(at.x(), at.y(), at.z());
                // Anything holding items is protected outright: replacing
                // a container deletes its contents with no drops.
                if (level.getBlockEntity(pos) instanceof Container) {
                    return true;
                }
                return PROTECTED_BLOCKS.contains(idOf(level.getBlockState(pos).getBlock()));
            }
        };
    }

    /**
     * Blocks a build must never overwrite. Containers are caught
     * dynamically by their block entity; these are the rest of the base
     * furniture and anything expensive or irreplaceable.
     */
    private static final java.util.Set<String> PROTECTED_BLOCKS = java.util.Set.of(
            "minecraft:crafting_table",
            "minecraft:furnace",
            "minecraft:blast_furnace",
            "minecraft:smoker",
            "minecraft:enchanting_table",
            "minecraft:anvil",
            "minecraft:beacon",
            "minecraft:spawner",
            "minecraft:end_portal_frame",
            "minecraft:bed",
            "minecraft:respawn_anchor",
            "minecraft:lodestone");

    /**
     * Farmland is tilled from soil rather than carried, so it is paid for
     * with a hoe instead of an inventory slot - matching how a player
     * makes it.
     */
    private static boolean spendBlockItem(ServerPlayer player, String blockId) {
        if ("minecraft:farmland".equals(blockId)) {
            return hasHoe(player);
        }
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) {
                continue;
            }
            if (!idOf(item.getBlock()).equals(blockId)) {
                continue;
            }
            stack.shrink(1);
            inventory.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            return true;
        }
        return false;
    }

    private static boolean spendWaterBucket(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(net.minecraft.world.item.Items.WATER_BUCKET)) {
                inventory.setItem(slot,
                        new ItemStack(net.minecraft.world.item.Items.BUCKET));
                return true;
            }
        }
        return false;
    }

    private static boolean hasHoe(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).getItem()
                    instanceof net.minecraft.world.item.HoeItem) {
                return true;
            }
        }
        return false;
    }

    private static net.minecraft.world.level.block.Block blockFor(String blockId) {
        var id = net.minecraft.resources.Identifier.tryParse(blockId);
        return id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }

    private static String idOf(net.minecraft.world.level.block.Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }
}
