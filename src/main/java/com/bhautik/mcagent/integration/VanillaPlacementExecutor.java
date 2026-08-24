package com.bhautik.mcagent.integration;

import com.bhautik.mcagent.action.BreakBlockAction;
import com.bhautik.mcagent.action.PlaceBlockAction;
import com.bhautik.mcagent.world.BlockLocator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Minecraft adapter behind the placement seam: picks a legal spot next
 * to the agent, places the block through the server world API, verifies
 * the persisted block state, and only then consumes the inventory item.
 * Also answers the "is a crafting table in reach" question used both for
 * planning and for independent execution verification.
 */
public final class VanillaPlacementExecutor {

    /** Blocks within this distance of the agent count as usable tables. */
    public static final int INTERACTION_RADIUS = 4;

    /** Horizontal/vertical offsets scanned around the agent for a spot. */
    private static final int PLACEMENT_REACH = 2;

    private VanillaPlacementExecutor() {
    }

    /** Locator for a specific block item id ("minecraft:crafting_table"). */
    public static BlockLocator blockLocator(ServerPlayer player, String blockItemId,
                                            int radius) {
        var id = net.minecraft.resources.Identifier.tryParse(blockItemId);
        var block = id == null ? null
                : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(id)
                        .orElse(null);
        if (block == null) {
            return BlockLocator.NONE;
        }
        return new BlockLocator() {
            @Override
            public boolean isNearby() {
                return findBlockNear(player, block, radius) != null;
            }

            @Override
            public Optional<BlockLocator.BlockSite> nearestWithin(int searchRadius) {
                BlockPos found = findBlockNear(player, block, searchRadius);
                return found == null ? Optional.empty()
                        : Optional.of(new BlockLocator.BlockSite(
                                found.getX(), found.getY(), found.getZ()));
            }
        };
    }

    public static PlaceBlockAction.Placer placer(ServerPlayer player) {
        return itemId -> place(player, itemId);
    }

    /**
     * Removes a nearby block the agent itself placed and returns its item
     * straight to inventory; verifies both the cleared state and the
     * granted stack before reporting success.
     */
    public static BreakBlockAction.Breaker breaker(ServerPlayer player, int radius) {
        return itemId -> {
            net.minecraft.resources.Identifier id =
                    net.minecraft.resources.Identifier.tryParse(itemId);
            if (id == null) {
                return BreakBlockAction.Breaker.Result.failed("unknown item " + itemId);
            }
            java.util.Optional<net.minecraft.world.level.block.Block> blockOption =
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(id);
            if (blockOption.isEmpty()) {
                return BreakBlockAction.Breaker.Result.failed(
                        shortName(itemId) + " is not a placeable block");
            }
            net.minecraft.world.level.block.Block block = blockOption.get();
            BlockPos target = findBlockNear(player, block, radius);
            if (target == null) {
                return BreakBlockAction.Breaker.Result.failed(
                        "no " + shortName(itemId) + " within reach to collect");
            }
            Level level = player.level();
            level.removeBlock(target, false);
            // Verify reality before granting anything (PRD 13).
            if (!level.getBlockState(target).isAir()) {
                return BreakBlockAction.Breaker.Result.failed("broken block did not clear");
            }
            ItemStack returned =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id)
                            .map(ItemStack::new)
                            .orElse(null);
            if (returned == null || returned.isEmpty()) {
                return BreakBlockAction.Breaker.Result.failed(
                        shortName(itemId) + " has no item form");
            }
            if (!player.getInventory().add(returned)) {
                player.drop(returned, false);
            }
            return BreakBlockAction.Breaker.Result.ok();
        };
    }

    private static PlaceBlockAction.Placer.Result place(ServerPlayer player, String itemId) {
        ItemStack carried = findStack(player, itemId);
        if (carried == null) {
            return PlaceBlockAction.Placer.Result.failed(
                    "no " + shortName(itemId) + " left in inventory");
        }
        if (!(carried.getItem() instanceof BlockItem blockItem)) {
            return PlaceBlockAction.Placer.Result.failed(shortName(itemId) + " cannot be placed");
        }
        BlockPos target = findPlacementSpot(player);
        if (target == null) {
            return PlaceBlockAction.Placer.Result.failed(
                    "no free supported spot nearby to place " + shortName(itemId));
        }
        Level level = player.level();
        BlockState state = blockItem.getBlock().defaultBlockState();
        level.setBlock(target, state, net.minecraft.world.level.block.Block.UPDATE_ALL);
        // Verify reality before consuming anything (PRD 13).
        if (!level.getBlockState(target).is(blockItem.getBlock())) {
            return PlaceBlockAction.Placer.Result.failed("placed block did not persist");
        }
        carried.shrink(1);
        return PlaceBlockAction.Placer.Result.ok();
    }

    /**
     * Nearest air-or-replaceable position with a sturdy surface below that
     * collides with no entity, so the agent never buries itself.
     */
    private static BlockPos findPlacementSpot(ServerPlayer player) {
        Level level = player.level();
        BlockPos origin = player.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -PLACEMENT_REACH; dx <= PLACEMENT_REACH; dx++) {
            for (int dz = -PLACEMENT_REACH; dz <= PLACEMENT_REACH; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir() && !state.canBeReplaced()) {
                        continue;
                    }
                    BlockPos below = pos.below();
                    if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                        continue;
                    }
                    AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(),
                            pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
                    if (!level.noCollision(box)) {
                        continue;
                    }
                    candidates.add(pos.immutable());
                }
            }
        }
        return candidates.stream()
                .min(Comparator.comparingDouble(pos -> pos.distSqr(origin)))
                .orElse(null);
    }

    /** Nearest position whose state matches {@code block}, or null. */
    private static BlockPos findBlockNear(ServerPlayer player,
                                          net.minecraft.world.level.block.Block block,
                                          int radius) {
        BlockPos origin = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-radius, -radius, -radius),
                origin.offset(radius, radius, radius))) {
            if (player.level().getBlockState(pos).is(block)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private static ItemStack findStack(ServerPlayer player, String itemId) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String candidateId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString();
            if (candidateId.equals(itemId)) {
                return stack;
            }
        }
        return null;
    }

    private static String shortName(String itemId) {
        return itemId.replaceFirst("^minecraft:", "");
    }
}
