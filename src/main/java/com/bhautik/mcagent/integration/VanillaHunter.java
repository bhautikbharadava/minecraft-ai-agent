package com.bhautik.mcagent.integration;

import com.bhautik.mcagent.action.Equipper;
import com.bhautik.mcagent.action.Hunter;
import com.bhautik.mcagent.item.DirectAcquisitions;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.AABB;

import java.util.Optional;

/**
 * Hunts passive animals for their drops. Mirrors {@link VanillaCombat}
 * (nearest-target scan, best sword, face, respect the attack cooldown)
 * but targets {@link Animal} instead of hostiles, and can also report
 * where prey is so the action can walk to it.
 */
public final class VanillaHunter {

    private VanillaHunter() {
    }

    public static Hunter hunter(ServerPlayer player, Equipper equipper) {
        return new Hunter() {
            @Override
            public Optional<Hunter.MobSite> nearest(String mobId, int radius) {
                Animal prey = nearestAnimal(player, mobId, radius);
                return prey == null ? Optional.empty()
                        : Optional.of(new Hunter.MobSite(
                                prey.blockPosition().getX(),
                                prey.blockPosition().getY(),
                                prey.blockPosition().getZ()));
            }

            @Override
            public int countNearby(String mobId, int radius) {
                var box = new AABB(player.blockPosition()).inflate(radius);
                int found = 0;
                for (Animal animal : player.level()
                        .getEntitiesOfClass(Animal.class, box)) {
                    if (animal.isAlive() && matches(animal, mobId)) {
                        found++;
                    }
                }
                return found;
            }

            @Override
            public boolean strike(String mobId, double reach) {
                if (player.isDeadOrDying() || player.isPassenger()) {
                    return false;
                }
                Animal prey = nearestAnimal(player, mobId, (int) Math.ceil(reach));
                if (prey == null || prey.distanceToSqr(player) > reach * reach) {
                    return false;
                }
                // Best-effort weapon; never blocks the swing.
                DirectAcquisitions.bestSwordFor(ownedItemIds(player))
                        .ifPresent(equipper::equip);
                double dx = prey.getX() - player.getX();
                double dz = prey.getZ() - player.getZ();
                player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
                if (player.getAttackStrengthScale(0.5f) < 0.9f) {
                    return false; // cooling down; not a landed blow
                }
                player.attack(prey);
                return true;
            }
        };
    }

    private static boolean matches(Animal animal, String mobId) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType())
                .toString().equals(mobId);
    }

    /** Nearest living animal whose entity id matches, within radius. */
    private static Animal nearestAnimal(ServerPlayer player, String mobId, int radius) {
        var box = new AABB(player.blockPosition()).inflate(radius);
        Animal nearest = null;
        double best = Double.MAX_VALUE;
        for (Animal animal : player.level().getEntitiesOfClass(Animal.class, box)) {
            if (!animal.isAlive() || !matches(animal, mobId)) {
                continue;
            }
            double distance = animal.distanceToSqr(player);
            if (distance < best) {
                best = distance;
                nearest = animal;
            }
        }
        return nearest;
    }

    private static java.util.Set<String> ownedItemIds(ServerPlayer player) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                ids.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
        }
        return ids;
    }
}
