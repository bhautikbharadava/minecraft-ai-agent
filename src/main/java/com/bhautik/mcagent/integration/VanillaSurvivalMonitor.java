package com.bhautik.mcagent.integration;

import com.bhautik.mcagent.action.RecoverAction;
import com.bhautik.mcagent.survival.SurvivalMonitor;
import com.bhautik.mcagent.survival.Threat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Minecraft adapter behind the survival seams: reads live health and
 * hunger for threat assessment, and eats the most nutritious food the
 * agent carries when recovery demands it.
 */
public final class VanillaSurvivalMonitor {

    private VanillaSurvivalMonitor() {
    }

    /** Air supply below this counts as a drowning emergency. */
    private static final int CRITICAL_AIR = 90;

    public static SurvivalMonitor monitor(ServerPlayer player) {
        return () -> {
            float health = player.getHealth();
            int hunger = player.getFoodData().getFoodLevel();
            if (player.getAirSupply() < CRITICAL_AIR) {
                return Threat.airEmergency("air critical (" + player.getAirSupply() + ")");
            }
            if (health <= SurvivalMonitor.CRITICAL_HEALTH) {
                return Threat.emergency("health critical (" + health + ")");
            }
            if (hunger <= SurvivalMonitor.CRITICAL_HUNGER) {
                return Threat.emergency("hunger critical (" + hunger + ")");
            }
            return Threat.NONE;
        };
    }

    /**
     * Drowning response: repeated upward swim impulses. Server-set
     * velocity only reaches the client player when hurtMarked forces a
     * motion sync — the same channel knockback uses.
     */
    public static com.bhautik.mcagent.action.SurfaceAction.Swimmer swimmer(
            ServerPlayer player) {
        return () -> {
            if (!player.isInWater()) {
                return false;
            }
            player.push(0, 0.35, 0);
            player.hurtMarked = true;
            return true;
        };
    }

    /**
     * Eats the single most nutritious food stack in inventory; returns
     * the nutrition applied, or 0 when nothing edible is carried or
     * hunger is already full.
     */
    public static RecoverAction.Feeder feeder(ServerPlayer player) {
        return () -> {
            if (player.getFoodData().getFoodLevel() >= 20) {
                return 0;
            }
            ItemStack best = null;
            int bestNutrition = 0;
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                FoodProperties food = stack.get(DataComponents.FOOD);
                if (food == null || food.nutrition() <= bestNutrition) {
                    continue;
                }
                best = stack;
                bestNutrition = food.nutrition();
            }
            if (best == null) {
                return 0;
            }
            FoodProperties food = best.get(DataComponents.FOOD);
            player.getFoodData().eat(food.nutrition(), food.saturation());
            best.shrink(1);
            return food.nutrition();
        };
    }
}
