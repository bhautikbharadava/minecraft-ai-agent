package com.bhautik.mcagent.integration;

import com.bhautik.mcagent.action.Breeder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Feeds nearby animals to start vanilla love mode. Vanilla owns the
 * pairing, the calf and the cooldown; this only hands over the food.
 */
public final class VanillaBreeder {

    private VanillaBreeder() {
    }

    public static Breeder breeder(ServerPlayer player) {
        return (mobId, foodItemId, reach) -> {
            int slot = findFood(player, foodItemId);
            if (slot < 0) {
                return false;
            }
            var box = new AABB(player.blockPosition()).inflate(reach);
            for (Animal animal : player.level().getEntitiesOfClass(Animal.class, box)) {
                if (!animal.isAlive() || !matches(animal, mobId)) {
                    continue;
                }
                if (animal.distanceToSqr(player) > reach * reach) {
                    continue;
                }
                // Skip babies and animals already in love / on cooldown.
                if (animal.isBaby() || animal.isInLove() || !animal.canFallInLove()) {
                    continue;
                }
                ItemStack food = player.getInventory().getItem(slot);
                if (!animal.isFood(food)) {
                    return false;
                }
                animal.setInLove(player);
                food.shrink(1);
                player.getInventory().setItem(slot,
                        food.isEmpty() ? ItemStack.EMPTY : food);
                return true;
            }
            return false;
        };
    }

    private static boolean matches(Animal animal, String mobId) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType())
                .toString().equals(mobId);
    }

    private static int findFood(ServerPlayer player, String foodItemId) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (id.equals(foodItemId)) {
                return slot;
            }
        }
        return -1;
    }
}
