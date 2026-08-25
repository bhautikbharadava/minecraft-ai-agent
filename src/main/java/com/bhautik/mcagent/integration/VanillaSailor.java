package com.bhautik.mcagent.integration;

import com.bhautik.mcagent.action.SailAction;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Vehicle seam for boats: spawning and mounting happen server-side
 * (entity + riding are server-authoritative), while steering is posted
 * to the client thread every tick because ridden boat physics are
 * client-authoritative in vanilla.
 */
public final class VanillaSailor {

    private VanillaSailor() {
    }

    public static SailAction.Sailor forPlayer(ServerPlayer player) {
        return new SailAction.Sailor() {
            private Boat boat;

            @Override
            public boolean launch() {
                if (boat != null && player.getVehicle() == boat) {
                    return true; // already sailing
                }
                ItemStack carried = findBoatStack(player);
                if (carried == null) {
                    return false;
                }
                Vec3 pos = player.position();
                Boat spawned = new Boat(EntityTypes.OAK_BOAT, player.level(),
                        () -> Items.OAK_BOAT);
                spawned.absSnapTo(pos.x, pos.y, pos.z, player.getYRot(), 0);
                if (!player.level().addFreshEntity(spawned)) {
                    return false;
                }
                boat = spawned;
                carried.shrink(1);
                return player.startRiding(boat);
            }

            @Override
            public void steer(int targetX, int targetZ) {
                if (boat == null) {
                    return;
                }
                ClientBridge.post(() -> {
                    double dx = targetX - boat.getX();
                    double dz = targetZ - boat.getZ();
                    float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                    boat.setYRot(yaw);
                    boat.setInput(false, false, true, false); // forward paddle
                });
            }

            @Override
            public boolean mounted() {
                return boat != null && boat.isAlive() && player.getVehicle() == boat;
            }

            @Override
            public void dismount() {
                player.stopRiding();
                if (boat != null) {
                    boat.discard();
                    boat = null;
                }
            }
        };
    }

    /** First boat item in inventory (any wood variant). */
    private static ItemStack findBoatStack(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()
                    && BuiltInId.of(stack.getItem()).endsWith("_boat")) {
                return stack;
            }
        }
        return null;
    }

    private static final class BuiltInId {
        private static String of(net.minecraft.world.item.Item item) {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item)
                    .toString();
        }
    }
}
