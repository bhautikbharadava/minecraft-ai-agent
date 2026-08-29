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
        BlockPos best = null;
        double nearest = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx += SCAN_STEP) {
            for (int dy = -radius; dy <= radius; dy += SCAN_STEP) {
                for (int dz = -radius; dz <= radius; dz += SCAN_STEP) {
                    var pos = origin.offset(dx, dy, dz);
                    var fluid = level.getFluidState(pos);
                    if (!fluid.isSource() || !fluid.getType().isSame(wanted)) {
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
                : Optional.of(new BlockLocator.BlockSite(best.getX(), best.getY(),
                        best.getZ()));
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
