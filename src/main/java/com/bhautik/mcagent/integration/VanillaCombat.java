package com.bhautik.mcagent.integration;

import com.bhautik.mcagent.action.MeleeAttacker;
import com.bhautik.mcagent.item.DirectAcquisitions;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

/**
 * Combat v0: melee-only response to hostiles. Scans for the nearest
 * monster in range, holds the best carried sword, faces it, and strikes
 * respecting the vanilla attack-cooldown scale. Passive fallbacks
 * (eat/surface/flee) stay with the survival layer.
 */
public final class VanillaCombat {

    private VanillaCombat() {
    }

    public static MeleeAttacker attacker(ServerPlayer player,
            com.bhautik.mcagent.action.Equipper equipper) {
        return range -> {
            if (player.isDeadOrDying() || player.isPassenger()) {
                return false;
            }
            var level = player.level();
            var box = new AABB(player.blockPosition()).inflate(range);
            Monster target = null;
            double nearest = Double.MAX_VALUE;
            for (Monster monster : level.getEntitiesOfClass(Monster.class, box)) {
                double distance = monster.distanceToSqr(player);
                if (distance < nearest) {
                    nearest = distance;
                    target = monster;
                }
            }
            if (target == null) {
                return false;
            }
            // Hold the best sword carried; best-effort, never blocks a strike.
            DirectAcquisitions.bestSwordFor(ownedItemIds(player))
                    .ifPresent(equipper::equip);
            // Face the target so the swing connects.
            double dx = target.getX() - player.getX();
            double dz = target.getZ() - player.getZ();
            player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
            if (player.getAttackStrengthScale(0.5f) < 0.9f) {
                return true; // engaged, waiting for cooldown
            }
            player.attack(target);
            return true;
        };
    }

    private static java.util.Set<String> ownedItemIds(ServerPlayer player) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                ids.add(net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).toString());
            }
        }
        return ids;
    }
}
