package com.bhautik.mcagent.integration;

import com.bhautik.mcagent.action.EnchantAction;
import com.bhautik.mcagent.world.BlockLocator;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Drives the real {@link EnchantmentMenu} at a nearby enchanting table.
 *
 * <p>Vanilla owns everything that matters: the per-player seed, the
 * three offers, the lapis cost and the XP spend. Re-implementing the
 * roll would drift from the game, so this adapter opens the same menu
 * the GUI opens and clicks a button on it. Server-authoritative for the
 * same reason equipping is (see the design decisions log).
 */
public final class VanillaEnchanter {

    /** Vanilla enchanting-menu slots. */
    private static final int ITEM_SLOT = 0;
    private static final int LAPIS_SLOT = 1;
    /** The table offers exactly three options. */
    private static final int OFFERS = 3;
    /** Vanilla charges one to three lapis per offer. */
    private static final int LAPIS_PER_ENCHANT = 3;
    /** Menu id for a container the client never sees. */
    private static final int SERVER_SIDE_CONTAINER_ID = 1;

    private static final String LAPIS_ITEM = "minecraft:lapis_lazuli";

    private VanillaEnchanter() {
    }

    public static EnchantAction.Enchanter enchanter(ServerPlayer player,
                                                    BlockLocator tableLocator) {
        return (itemId, minLevel) -> {
            BlockPos table = nearestTable(tableLocator);
            if (table == null) {
                return EnchantAction.Enchanter.Result
                        .failed("no enchanting table within reach");
            }
            int itemSlot = findSlot(player, itemId, false);
            if (itemSlot < 0) {
                return EnchantAction.Enchanter.Result
                        .failed("not carrying " + shortName(itemId));
            }
            int lapisSlot = findSlot(player, LAPIS_ITEM, false);
            if (lapisSlot < 0) {
                return EnchantAction.Enchanter.Result.failed("no lapis lazuli carried");
            }

            var access = ContainerLevelAccess.create(player.level(), table);
            // The menu is driven server-side and never sent to the client,
            // so the container id is bookkeeping only.
            var menu = new EnchantmentMenu(SERVER_SIDE_CONTAINER_ID,
                    player.getInventory(), access);

            // Move the item + lapis into the menu, then let vanilla roll
            // the offers exactly as it would for a player at the table.
            // Put ONE item and at most three lapis into the menu, and
            // remember how much lapis went in. Handing the menu whole
            // stacks and then writing back only what it returned destroyed
            // the remainder: a slot of 64 lapis came back holding 1.
            ItemStack target = player.getInventory().getItem(itemSlot).copyWithCount(1);
            int lapisCarried = player.getInventory().getItem(lapisSlot).getCount();
            ItemStack payment = player.getInventory().getItem(lapisSlot)
                    .copyWithCount(Math.min(lapisCarried, LAPIS_PER_ENCHANT));
            int lapisOffered = payment.getCount();

            menu.getSlot(ITEM_SLOT).set(target);
            menu.getSlot(LAPIS_SLOT).set(payment);
            menu.slotsChanged(new SimpleContainer(target, payment));

            int choice = bestAffordableOffer(menu, player.experienceLevel, minLevel);
            if (choice < 0) {
                menu.removed(player);
                return EnchantAction.Enchanter.Result.failed(
                        describeUnaffordable(menu, player.experienceLevel, minLevel));
            }

            // Consume the originals only once vanilla accepts the click,
            // so a refused enchant never eats the item or the lapis.
            if (!menu.clickMenuButton(player, choice)) {
                menu.removed(player);
                return EnchantAction.Enchanter.Result
                        .failed("table refused offer " + (choice + 1));
            }

            ItemStack enchanted = menu.getSlot(ITEM_SLOT).getItem();
            if (enchanted.isEmpty() || enchanted.getEnchantments().isEmpty()) {
                menu.removed(player);
                return EnchantAction.Enchanter.Result
                        .failed("table produced no enchantment");
            }

            // Pay for exactly what was used and leave the rest of each
            // stack alone. clickMenuButton already spent the XP levels.
            var inventory = player.getInventory();
            ItemStack sourceStack = inventory.getItem(itemSlot);
            sourceStack.shrink(1); // the one copy that went into the menu
            inventory.setItem(itemSlot,
                    sourceStack.isEmpty() ? ItemStack.EMPTY : sourceStack);
            ItemStack enchantedCopy = enchanted.copy();
            if (!inventory.add(enchantedCopy)) {
                player.drop(enchantedCopy, false); // bag full; better than deleting it
            }
            int lapisSpent = lapisOffered - menu.getSlot(LAPIS_SLOT).getItem().getCount();
            if (lapisSpent > 0) {
                ItemStack lapisStack = inventory.getItem(lapisSlot);
                lapisStack.shrink(lapisSpent);
                inventory.setItem(lapisSlot,
                        lapisStack.isEmpty() ? ItemStack.EMPTY : lapisStack);
            }
            menu.getSlot(ITEM_SLOT).set(ItemStack.EMPTY);
            menu.getSlot(LAPIS_SLOT).set(ItemStack.EMPTY);
            menu.removed(player);
            player.containerMenu.broadcastChanges();
            return EnchantAction.Enchanter.Result.ok();
        };
    }

    /**
     * Highest-value offer the agent can actually pay for. Vanilla lists
     * offers cheapest-first, so scanning downward picks the best one.
     */
    private static int bestAffordableOffer(EnchantmentMenu menu, int xpLevel,
                                           int minLevel) {
        for (int offer = OFFERS - 1; offer >= 0; offer--) {
            int cost = menu.costs[offer];
            if (cost <= 0) {
                continue;
            }
            // Vanilla charges `cost` levels but also requires the player
            // to hold at least (offer+1) lapis.
            if (cost <= xpLevel && cost >= minLevel) {
                return offer;
            }
        }
        return -1;
    }

    private static String describeUnaffordable(EnchantmentMenu menu, int xpLevel,
                                               int minLevel) {
        int best = 0;
        for (int cost : menu.costs) {
            best = Math.max(best, cost);
        }
        if (best <= 0) {
            return "table offered no enchantment (needs more bookshelves or a "
                    + "different item)";
        }
        if (best < minLevel) {
            return "best offer is level " + best + ", below requested " + minLevel
                    + " (add bookshelves to raise offers)";
        }
        return "need level " + best + " to enchant, have " + xpLevel;
    }

    private static BlockPos nearestTable(BlockLocator locator) {
        return locator.nearestWithin(VanillaPlacementExecutor.INTERACTION_RADIUS)
                .map(site -> new BlockPos(site.x(), site.y(), site.z()))
                .orElse(null);
    }

    /** First inventory slot holding the item; optionally requires no damage. */
    private static int findSlot(ServerPlayer player, String itemId, boolean pristine) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString();
            if (!id.equals(itemId)) {
                continue;
            }
            // Never re-enchant something already enchanted.
            if (!stack.getEnchantments().isEmpty()) {
                continue;
            }
            if (pristine && stack.isDamaged()) {
                continue;
            }
            return slot;
        }
        return -1;
    }

    private static String shortName(String itemId) {
        return itemId.replaceFirst("^minecraft:", "");
    }
}
