package com.bhautik.mcagent.integration;

import com.bhautik.mcagent.action.FluidHandler;
import com.bhautik.mcagent.world.BlockLocator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Bucket work against the live world: find fluid sources, fill from
 * water, and pour onto lava so vanilla's own fluid interaction turns it
 * into obsidian.
 *
 * <p>Server-authoritative, like equipping: the integrated server's
 * inventory copy is mutated and vanilla syncs the client.
 */
public final class VanillaFluidHandler {

    /** Blocks scanned outward when looking for a fluid source. */
    private static final int SCAN_STEP = 1;

    private VanillaFluidHandler() {
    }

    public static FluidHandler handler(ServerPlayer player) {
        return new FluidHandler() {
            @Override
            public Optional<BlockLocator.BlockSite> nearest(String fluidId, int radius) {
                return nearestSource(player, fluidId, radius);
            }

            @Override
            public Optional<BlockLocator.BlockSite> nearestPourable(int radius) {
                var level = player.level();
                var origin = player.blockPosition();
                for (int shell = 0; shell <= radius; shell++) {
                    for (int dx = -shell; dx <= shell; dx++) {
                        for (int dy = -shell; dy <= shell; dy++) {
                            for (int dz = -shell; dz <= shell; dz++) {
                                if (Math.max(Math.max(Math.abs(dx), Math.abs(dy)),
                                        Math.abs(dz)) != shell) {
                                    continue;
                                }
                                var pos = origin.offset(dx, dy, dz);
                                var fluid = level.getFluidState(pos);
                                if (!fluid.isSource() || !fluid.getType()
                                        .isSame(net.minecraft.world.level.material.Fluids.LAVA)) {
                                    continue;
                                }
                                // Room above is what makes it pourable.
                                if (!VanillaPlacementExecutor.isClear(
                                        level.getBlockState(pos.above()))) {
                                    continue;
                                }
                                return Optional.of(new BlockLocator.BlockSite(
                                        pos.getX(), pos.getY(), pos.getZ()));
                            }
                        }
                    }
                }
                return Optional.empty();
            }

            @Override
            public boolean carriesWater() {
                return findItem(player, Items.WATER_BUCKET) >= 0;
            }

            @Override
            public boolean fillFrom(BlockLocator.BlockSite waterSite) {
                int slot = findItem(player, Items.BUCKET);
                if (slot < 0) {
                    return false;
                }
                var pos = new BlockPos(waterSite.x(), waterSite.y(), waterSite.z());
                var level = player.level();
                if (!level.getFluidState(pos).isSource()) {
                    return false;
                }
                // Take the source and hand back a filled bucket.
                level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR
                        .defaultBlockState(), 3);
                player.getInventory().setItem(slot, new ItemStack(Items.WATER_BUCKET));
                return true;
            }

            @Override
            public boolean pourOnto(BlockLocator.BlockSite lavaSite) {
                int slot = findItem(player, Items.WATER_BUCKET);
                if (slot < 0) {
                    return false;
                }
                var lavaPos = new BlockPos(lavaSite.x(), lavaSite.y(), lavaSite.z());
                var level = player.level();
                if (!level.getFluidState(lavaPos).isSource()) {
                    return false;
                }
                // Empty into the space ABOVE the lava and let vanilla run the
                // water-meets-lava rule; setting obsidian directly would be
                // pretend-work that skips the real mechanic.
                var target = lavaPos.above();
                ItemStack bucket = player.getInventory().getItem(slot);
                if (!(bucket.getItem() instanceof BucketItem water)) {
                    return false;
                }
                var hit = new BlockHitResult(Vec3.atCenterOf(target), Direction.UP,
                        target, false);
                boolean emptied = water.emptyContents(player, level, target, hit);
                if (!emptied) {
                    return false;
                }
                player.getInventory().setItem(slot, new ItemStack(Items.BUCKET));
                return true;
            }
        };
    }

    /**
     * Nearest true source block of the fluid. Flowing fluid is skipped —
     * only sources fill a bucket or convert to obsidian.
     */
    private static Optional<BlockLocator.BlockSite> nearestSource(ServerPlayer player,
                                                                  String fluidId,
                                                                  int radius) {
        var level = player.level();
        var origin = player.blockPosition();
        var wanted = BuiltInRegistries.FLUID
                .getOptional(net.minecraft.resources.Identifier.parse(fluidId))
                .orElse(null);
        if (wanted == null) {
            return Optional.empty();
        }
        // Expanding shells with an early exit. Scanning the whole cube was
        // 65^3 lookups EVERY tick at radius 32; searching outward returns
        // the nearest source by construction and stops as soon as it finds
        // one, which is what makes a wider search affordable at all.
        BlockPos best = null;
        for (int shell = 0; shell <= radius && best == null; shell += SCAN_STEP) {
            best = scanShell(level, origin, wanted, shell);
        }
        return best == null ? Optional.empty()
                : Optional.of(new BlockLocator.BlockSite(best.getX(), best.getY(),
                        best.getZ()));
    }

    /**
     * Only the surface of the cube at this radius, so an outward search
     * never re-checks ground a smaller shell already covered.
     */
    private static BlockPos scanShell(net.minecraft.world.level.Level level,
                                      BlockPos origin,
                                      net.minecraft.world.level.material.Fluid wanted,
                                      int shell) {
        for (int dx = -shell; dx <= shell; dx++) {
            for (int dy = -shell; dy <= shell; dy++) {
                for (int dz = -shell; dz <= shell; dz++) {
                    if (Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz))
                            != shell) {
                        continue; // interior; an earlier shell had it
                    }
                    var pos = origin.offset(dx, dy, dz);
                    var fluid = level.getFluidState(pos);
                    if (fluid.isSource() && fluid.getType().isSame(wanted)) {
                        return pos.immutable();
                    }
                }
            }
        }
        return null;
    }

    private static int findItem(ServerPlayer player, net.minecraft.world.item.Item item) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                return slot;
            }
        }
        return -1;
    }
}
