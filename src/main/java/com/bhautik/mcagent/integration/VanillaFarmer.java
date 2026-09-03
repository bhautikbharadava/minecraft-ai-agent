package com.bhautik.mcagent.integration;

import com.bhautik.mcagent.action.Farmer;
import com.bhautik.mcagent.world.BlockLocator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

/**
 * Crop farming against the live world. Server-authoritative, matching
 * how block placement already works in this codebase: the integrated
 * server's state is mutated and vanilla syncs the client.
 *
 * <p>Tilling still requires a carried hoe, so the plan pays the real
 * vanilla cost rather than conjuring farmland for free.
 */
public final class VanillaFarmer {

    /** Farmland dries out beyond this distance from water. */
    private static final int WATER_RANGE = 4;
    /** Vertical slack when following uneven ground across a plot. */
    private static final int SURFACE_SCAN = 2;

    private VanillaFarmer() {
    }

    public static Farmer farmer(ServerPlayer player) {
        return new Farmer() {
            @Override
            public Optional<BlockLocator.BlockSite> tillableSpot(int radius) {
                var level = player.level();
                var origin = player.blockPosition();
                BlockPos best = null;
                double nearest = Double.MAX_VALUE;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -3; dy <= 3; dy++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            var pos = origin.offset(dx, dy, dz);
                            var state = level.getBlockState(pos);
                            boolean soil = state.is(Blocks.GRASS_BLOCK)
                                    || state.is(Blocks.DIRT)
                                    || state.is(Blocks.FARMLAND);
                            if (!soil || !VanillaPlacementExecutor.isClear(level.getBlockState(pos.above()))) {
                                continue;
                            }
                            if (!nearWater(player, pos)) {
                                continue;
                            }
                            double distance = pos.distSqr(origin);
                            if (distance < nearest) {
                                nearest = distance;
                                best = pos.immutable();
                            }
                        }
                    }
                }
                return best == null ? Optional.empty()
                        : Optional.of(new BlockLocator.BlockSite(best.getX(),
                                best.getY(), best.getZ()));
            }

            @Override
            public int tillPlot(BlockLocator.BlockSite centre, int radius) {
                if (findHoe(player) < 0) {
                    return 0;
                }
                var level = player.level();
                var origin = new BlockPos(centre.x(), centre.y(), centre.z());
                int tilled = 0;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        // Follow the surface instead of assuming the plot
                        // is flat: scanning only the centre's own Y missed
                        // almost every column on natural ground.
                        var pos = surfaceSoil(player, origin.offset(dx, 0, dz));
                        if (pos == null) {
                            continue;
                        }
                        if (level.getBlockState(pos).is(Blocks.FARMLAND)) {
                            tilled++; // already workable
                            continue;
                        }
                        if (!nearWater(player, pos)) {
                            continue; // would dry out and revert
                        }
                        // Clear whatever is growing on top; farmland with a
                        // vine over it has nowhere to put a seed.
                        if (!level.getBlockState(pos.above()).isAir()) {
                            level.destroyBlock(pos.above(), true, player);
                        }
                        level.setBlock(pos, Blocks.FARMLAND.defaultBlockState(), 3);
                        if (level.getBlockState(pos).is(Blocks.FARMLAND)) {
                            tilled++;
                        }
                    }
                }
                return tilled;
            }

            @Override
            public int sowAll(String seedItemId, int radius) {
                var crop = cropFor(seedItemId);
                if (crop == null) {
                    return 0;
                }
                var level = player.level();
                var origin = player.blockPosition();
                int sown = 0;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -3; dy <= 3; dy++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            int slot = findItem(player, seedItemId);
                            if (slot < 0) {
                                return sown; // out of seed
                            }
                            var pos = origin.offset(dx, dy, dz);
                            if (!level.getBlockState(pos).is(Blocks.FARMLAND)
                                    || !VanillaPlacementExecutor.isClear(level.getBlockState(pos.above()))) {
                                continue;
                            }
                            // Break any grass or vine standing there first,
                            // so it drops instead of being erased by the crop.
                            if (!level.getBlockState(pos.above()).isAir()) {
                                level.destroyBlock(pos.above(), true, player);
                            }
                            level.setBlock(pos.above(), crop.defaultBlockState(), 3);
                            ItemStack seeds = player.getInventory().getItem(slot);
                            seeds.shrink(1);
                            player.getInventory().setItem(slot,
                                    seeds.isEmpty() ? ItemStack.EMPTY : seeds);
                            sown++;
                        }
                    }
                }
                return sown;
            }

            @Override
            public int cropsPlanted(String cropBlockId, int radius) {
                return countCrops(player, cropBlockId, radius, false);
            }

            @Override
            public int cropsRipe(String cropBlockId, int radius) {
                return countCrops(player, cropBlockId, radius, true);
            }

            @Override
            public boolean hurryGrowth(String cropBlockId, int radius) {
                int slot = findItem(player, "minecraft:bone_meal");
                if (slot < 0) {
                    return false;
                }
                var target = firstCrop(player, cropBlockId, radius, false);
                if (target == null) {
                    return false;
                }
                ItemStack meal = player.getInventory().getItem(slot);
                boolean grew = BoneMealItem.growCrop(meal, player.level(), target);
                if (grew) {
                    meal.shrink(1);
                    player.getInventory().setItem(slot,
                            meal.isEmpty() ? ItemStack.EMPTY : meal);
                }
                return grew;
            }

            @Override
            public int harvestRipe(String cropBlockId, int radius) {
                var level = player.level();
                var origin = player.blockPosition();
                var block = blockFor(cropBlockId);
                if (block == null) {
                    return 0;
                }
                int cut = 0;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -3; dy <= 3; dy++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            var pos = origin.offset(dx, dy, dz);
                            var state = level.getBlockState(pos);
                            if (!state.is(block) || !isRipe(state)) {
                                continue;
                            }
                            // Break with drops so wheat AND seeds fall,
                            // keeping the farm self-seeding.
                            if (level.destroyBlock(pos, true, player)) {
                                cut++;
                            }
                        }
                    }
                }
                return cut;
            }
        };
    }

    /**
     * The farmable surface block in this column: soil or existing
     * farmland with open air above it, searched a couple of blocks
     * either side of the plot's own level so uneven ground still farms.
     */
    private static BlockPos surfaceSoil(ServerPlayer player, BlockPos column) {
        var level = player.level();
        for (int dy = SURFACE_SCAN; dy >= -SURFACE_SCAN; dy--) {
            var pos = column.offset(0, dy, 0);
            var state = level.getBlockState(pos);
            boolean soil = state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                    || state.is(Blocks.FARMLAND);
            if (soil && VanillaPlacementExecutor.isClear(level.getBlockState(pos.above()))) {
                return pos.immutable();
            }
        }
        return null;
    }

    private static boolean nearWater(ServerPlayer player, BlockPos pos) {
        var level = player.level();
        for (int dx = -WATER_RANGE; dx <= WATER_RANGE; dx++) {
            for (int dz = -WATER_RANGE; dz <= WATER_RANGE; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (level.getFluidState(pos.offset(dx, dy, dz))
                            .is(net.minecraft.world.level.material.Fluids.WATER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int countCrops(ServerPlayer player, String cropBlockId, int radius,
                                  boolean ripeOnly) {
        var level = player.level();
        var origin = player.blockPosition();
        var block = blockFor(cropBlockId);
        if (block == null) {
            return 0;
        }
        int found = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    var state = level.getBlockState(origin.offset(dx, dy, dz));
                    if (state.is(block) && (!ripeOnly || isRipe(state))) {
                        found++;
                    }
                }
            }
        }
        return found;
    }

    private static BlockPos firstCrop(ServerPlayer player, String cropBlockId,
                                      int radius, boolean ripeOnly) {
        var level = player.level();
        var origin = player.blockPosition();
        var block = blockFor(cropBlockId);
        if (block == null) {
            return null;
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    var pos = origin.offset(dx, dy, dz);
                    var state = level.getBlockState(pos);
                    if (state.is(block) && isRipe(state) == ripeOnly) {
                        return pos.immutable();
                    }
                }
            }
        }
        return null;
    }

    private static boolean isRipe(BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    private static net.minecraft.world.level.block.Block blockFor(String blockId) {
        var id = net.minecraft.resources.Identifier.tryParse(blockId);
        return id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }

    /** The crop block a seed produces. */
    private static net.minecraft.world.level.block.Block cropFor(String seedItemId) {
        return switch (seedItemId) {
            case "minecraft:wheat_seeds" -> Blocks.WHEAT;
            case "minecraft:carrot" -> Blocks.CARROTS;
            case "minecraft:potato" -> Blocks.POTATOES;
            case "minecraft:beetroot_seeds" -> Blocks.BEETROOTS;
            default -> null;
        };
    }

    private static int findHoe(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).getItem() instanceof HoeItem) {
                return slot;
            }
        }
        return -1;
    }

    private static int findItem(ServerPlayer player, String itemId) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
                return slot;
            }
        }
        return -1;
    }
}
