package com.bhautik.mcagent.planner;

import com.bhautik.mcagent.action.AgentAction;
import com.bhautik.mcagent.action.BreakBlockAction;
import com.bhautik.mcagent.action.CraftAction;
import com.bhautik.mcagent.action.EnchantAction;
import com.bhautik.mcagent.action.Equipper;
import com.bhautik.mcagent.action.BreedAction;
import com.bhautik.mcagent.action.Breeder;
import com.bhautik.mcagent.action.FluidHandler;
import com.bhautik.mcagent.action.HuntAction;
import com.bhautik.mcagent.action.Hunter;
import com.bhautik.mcagent.action.MakeObsidianAction;
import com.bhautik.mcagent.action.XpFarmAction;
import com.bhautik.mcagent.action.MineAction;
import com.bhautik.mcagent.action.MoveAction;
import com.bhautik.mcagent.action.PlaceBlockAction;
import com.bhautik.mcagent.action.SmeltAction;
import com.bhautik.mcagent.crafting.RecipeResolver;
import com.bhautik.mcagent.crafting.SmeltingResolver;
import com.bhautik.mcagent.integration.BaritoneIntegration;
import com.bhautik.mcagent.item.DirectAcquisitions;
import com.bhautik.mcagent.item.MobDrops;
import com.bhautik.mcagent.world.BiomeSensor;
import com.bhautik.mcagent.world.BlockLocator;
import com.bhautik.mcagent.world.DistanceSensor;
import com.bhautik.mcagent.world.PositionAnchor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;

/**
 * Expands a resource gap into an ordered action list by resolving
 * dependencies recursively, always crediting current inventory:
 * mine what is directly acquirable, then smelt what has a furnace
 * recipe, then craft the rest from real vanilla recipes.
 *
 * Smelting is preferred over crafting for smeltable outputs because
 * their only crafting routes are closed loops (nuggets <-> ingots);
 * ores enter the plan through mining instead.
 *
 * Emitted plans are post-order: dependencies appear before dependents,
 * which lets the single-action runner execute them sequentially.
 *
 * Recipes wider or taller than the inventory grid insert crafting-table
 * steps; furnace recipes insert furnace steps. Utility blocks are walked
 * to when one exists nearby, otherwise acquired, placed once per plan,
 * and collected again at the end.
 */
public final class Planner {
    /** The block item the agent uses for 3x3 crafting. */
    public static final String CRAFTING_TABLE_ITEM = "minecraft:crafting_table";
    /** The block item the agent uses for smelting. */
    public static final String FURNACE_ITEM = "minecraft:furnace";
    /** Standard fuel the planner demands for furnace runs. */
    public static final String FUEL_ITEM = "minecraft:coal";
    /** Lighting carried for tunnel safety (mob-spawn prevention). */
    public static final String TORCH_ITEM = "minecraft:torch";
    /** Storage block of the home base. */
    public static final String BASE_CHEST_ITEM = "minecraft:chest";
    /** The block item the agent uses for enchanting. */
    public static final String ENCHANTING_TABLE_ITEM = "minecraft:enchanting_table";
    /** Made from lava rather than searched for; see MakeObsidianAction. */
    public static final String OBSIDIAN_ITEM = "minecraft:obsidian";
    /** Bucket needed before any obsidian can be made. */
    public static final String BUCKET_ITEM = "minecraft:bucket";
    /** Currency the enchanting table consumes per enchant (vanilla: 1-3). */
    public static final String LAPIS_ITEM = "minecraft:lapis_lazuli";
    /** Lapis secured before an enchant so every offer is payable. */
    public static final int LAPIS_PER_ENCHANT = 3;
    /**
     * Ore mined when an enchant needs more XP. Coal is the pick because
     * it drops XP and needs only a wooden pickaxe, so the farm is never
     * gated behind a tool the agent lacks.
     */
    public static final String XP_FARM_ORE = "minecraft:coal_ore";
    /** Mining plans trigger a torch top-up when stock drops below this. */
    public static final int MIN_TORCHES = 16;
    /** Food the agent keeps pre-stocked before long goals. */
    public static final String FOOD_UPKEEP_ITEM = "minecraft:sweet_berries";
    /** Trigger when fewer than this many edibles are carried. */
    public static final int MIN_EDIBLES = 6;
    /** Upkeep crafts/forages this much before the goal. */
    public static final int FOOD_STACK_TARGET = 12;
    /** Top-ups fill a full stack so long digs never run dark. */
    public static final int TORCH_STACK_TARGET = 64;

    /** How far the agent will walk to reach an existing utility block. */
    public static final int BLOCK_SEARCH_RADIUS = 48;

    /** Blocks (squared) considered close enough to use a utility block. */
    public static final double BLOCK_ARRIVE_DISTANCE_SQ = 9.0;

    /**
     * Execution seams handed to emitted actions: real grid crafting,
     * real furnace cooking, real block placement and collection, world
     * checks that verify all of it, continuous tunnel lighting, and the
     * live biome/anchor used to chain exploration for gated resources.
     */
    public record Environment(CraftAction.Crafter crafter,
                              SmeltAction.Smelter smelter,
                              PlaceBlockAction.Placer placer,
                              BreakBlockAction.Breaker breaker,
                              RecipeResolver resolver,
                              SmeltingResolver smeltingResolver,
                              BlockLocator tableLocator,
                              BlockLocator furnaceLocator,
                              BlockLocator enchantTableLocator,
                              DistanceSensor distanceSensor,
                              BiomeSensor biomeSensor,
                              PositionAnchor anchor,
                              Equipper equipper,
                              Hunter hunter,
                              com.bhautik.mcagent.world.XpSensor xpSensor,
                              Breeder breeder,
                              FluidHandler fluids,
                              IntSupplier obsidianBlockCount,
                              /** Same-ore blocks exposed near the agent, by block id. */
                              Function<String, Integer> nearbyBlockCount) {
    }

    private final BaritoneIntegration baritoneIntegration;

    public Planner(BaritoneIntegration baritoneIntegration) {
        this.baritoneIntegration = baritoneIntegration;
    }

    /** Planning refused because the goal is impossible right now. */
    public static final class PlanningException extends RuntimeException {
        public PlanningException(String message) {
            super(message);
        }
    }

    /**
     * @param plannedCounts inventory counts captured at plan time (credit
     *                      what the player already owns)
     * @param ownedItemIds  inventory item ids used for tool gating
     * @param liveCounts    live counts per item id, for action verification
     * @param environment   execution seams for crafting, smelting, placement
     */
    public List<AgentAction> planAcquisition(RecipeResolver resolver,
                                             Function<String, Integer> plannedCounts,
                                             Set<String> ownedItemIds,
                                             ToIntFunction<String> liveCounts,
                                             Environment environment,
                                             String itemId,
                                             int targetCount) {
        return planAcquisition(resolver, plannedCounts, ownedItemIds, liveCounts,
                environment, List.of(Map.entry(itemId, targetCount)));
    }

    /**
     * Multi-root planning (PRD UC-09): all roots share one Expansion, so
     * a full armor set mines its total diamond need once instead of four
     * independent ladders re-gathering the same intermediates.
     */
     public List<AgentAction> planAcquisition(RecipeResolver resolver,
                                             Function<String, Integer> plannedCounts,
                                             Set<String> ownedItemIds,
                                             ToIntFunction<String> liveCounts,
                                             Environment environment,
                                             List<Map.Entry<String, Integer>> roots) {
        return planAcquisition(resolver, plannedCounts, ownedItemIds, liveCounts,
                environment, roots, new LinkedHashSet<>());
    }

    /**
     * Multi-root planning that also reports every item id the expansion
     * demanded, so callers can tell which stored supplies the plan
     * actually relies on instead of hauling a whole chest around.
     */
     public List<AgentAction> planAcquisition(RecipeResolver resolver,
                                             Function<String, Integer> plannedCounts,
                                             Set<String> ownedItemIds,
                                             ToIntFunction<String> liveCounts,
                                             Environment environment,
                                             List<Map.Entry<String, Integer>> roots,
                                             Set<String> demandedOut) {
        Expansion expansion = new Expansion(resolver, plannedCounts, ownedItemIds,
                liveCounts, environment);
        expansion.demanded = demandedOut;
        for (Map.Entry<String, Integer> root : roots) {
            expansion.expand(root.getKey(), root.getValue(), true);
        }
        List<AgentAction> plan = new ArrayList<>(expansion.plan);
        boolean goalIsFood = roots.stream().anyMatch(e -> FOOD_UPKEEP_ITEM.equals(e.getKey()));
        prependUpkeep(plan, resolver, plannedCounts, ownedItemIds, liveCounts,
                environment, goalIsFood);
        // A table or furnace this plan placed is picked back up once
        // everything else ran; pre-existing world blocks stay put.
        for (String placed : expansion.placedBlocks) {
            plan.add(new BreakBlockAction(placed,
                    environment.breaker(),
                    () -> liveCounts.applyAsInt(placed)));
        }
        return plan;
    }

    /**
     * Enchanting plan (docs/ENCHANTING.md): secure lapis, reach an
     * enchanting table — walking to one, or crafting and placing it
     * through the same machinery that provides crafting tables — then
     * enchant and pick the table back up.
     *
     * <p>The XP levels the table charges are NOT planned here: mining
     * the dependencies grants XP incidentally, and the enchant step
     * fails honestly when the level is still short.
     */
    public List<AgentAction> planEnchant(RecipeResolver resolver,
                                         Function<String, Integer> plannedCounts,
                                         Set<String> ownedItemIds,
                                         ToIntFunction<String> liveCounts,
                                         Environment environment,
                                         EnchantAction.Enchanter enchanter,
                                         IntSupplier enchantedCount,
                                         String itemId,
                                         int minLevel,
                                         BlockLocator.BlockSite homeSite) {
        Expansion expansion = new Expansion(resolver, plannedCounts, ownedItemIds,
                liveCounts, environment);
        // The item itself: acquire it when the agent is not carrying one.
        expansion.expand(itemId, 1, true);
        int carriedLapis = Math.max(plannedCounts.apply(LAPIS_ITEM), 0);
        if (carriedLapis < LAPIS_PER_ENCHANT) {
            expansion.expand(LAPIS_ITEM, LAPIS_PER_ENCHANT - carriedLapis, true);
        }
        // The enchanting setup is base infrastructure, not a portable tool:
        // build it once at home and walk back to it, rather than dropping a
        // table wherever the agent happens to stand and breaking it again.
        // Gather-before-place still holds - every mining step is queued
        // above, so the placement cannot be dug through.
        expansion.planEnchantingSetup(homeSite);

        List<AgentAction> plan = new ArrayList<>(expansion.plan);
        // An explicit level request is a promise to reach it, so farm the
        // shortfall. Without one the agent spends whatever mining earned.
        if (minLevel > 1 && environment.xpSensor().level() < minLevel) {
            plan.add(new XpFarmAction(XP_FARM_ORE, minLevel,
                    environment.xpSensor(), baritoneIntegration,
                    environment.anchor()));
        }
        plan.add(new EnchantAction(itemId, minLevel, enchanter,
                environment.enchantTableLocator()::isNearby, enchantedCount));
        // Same upkeep every other mining plan gets: enchant chains dig for
        // lapis, sugar cane and obsidian, so they need torches and food too.
        prependUpkeep(plan, resolver, plannedCounts, ownedItemIds, liveCounts,
                environment, false);
        for (String placed : expansion.placedBlocks) {
            plan.add(new BreakBlockAction(placed,
                    environment.breaker(),
                    () -> liveCounts.applyAsInt(placed)));
        }
        return plan;
    }

    /**
     * Prepends torch and food upkeep to any mining plan (PRD 15
     * background tier). Shared by every planning entry point — keeping
     * this in one place is what stops a new plan type from silently
     * shipping without upkeep, which is exactly how enchanting plans
     * started digging with no torches.
     */
    private void prependUpkeep(List<AgentAction> plan, RecipeResolver resolver,
                               Function<String, Integer> plannedCounts,
                               Set<String> ownedItemIds,
                               ToIntFunction<String> liveCounts,
                               Environment environment, boolean goalIsFood) {
        boolean minesSomething = plan.stream().anyMatch(step -> step instanceof MineAction);
        if (!minesSomething) {
            return;
        }
        int carriedTorches = Math.max(plannedCounts.apply(TORCH_ITEM), 0);
        if (carriedTorches < MIN_TORCHES) {
            Expansion torchRun = new Expansion(resolver, plannedCounts, ownedItemIds,
                    liveCounts, environment);
            try {
                torchRun.expand(TORCH_ITEM, TORCH_STACK_TARGET - carriedTorches, true);
                plan.addAll(0, torchRun.plan);
                com.bhautik.mcagent.McAgent.LOGGER.info(
                        "[Planner] Torch upkeep prepended (plan now {} steps)", plan.size());
            } catch (PlanningException unresolvableTorches) {
                // No torch route: mine anyway; survival handles the rest.
            }
        }
        // Food upkeep: keep edibles stocked before mining-heavy goals, so
        // starvation interrupts stay rare. Mirrors torch upkeep.
        int carriedEdibles = Math.max(plannedCounts.apply(FOOD_UPKEEP_ITEM), 0);
        if (!goalIsFood && carriedEdibles < MIN_EDIBLES) {
            Expansion foodRun = new Expansion(resolver, plannedCounts, ownedItemIds,
                    liveCounts, environment);
            try {
                foodRun.expand(FOOD_UPKEEP_ITEM, FOOD_STACK_TARGET - carriedEdibles, true);
                plan.addAll(0, foodRun.plan);
                com.bhautik.mcagent.McAgent.LOGGER.info(
                        "[Planner] Food upkeep prepended (plan now {} steps)", plan.size());
            } catch (PlanningException unresolvableFood) {
                // No food route: proceed; survival handles the rest reactively.
            }
        }
    }

    /**
     * Calves worth breeding for a hunt of this size: enough to replace
     * what will be killed beyond the breeding pair left standing, capped
     * so a huge order does not turn into an endless ranching session.
     */
    private static int calvesFor(int kills) {
        return Math.min(Math.max(kills, 1), MAX_CALVES_PER_PLAN);
    }

    /** Ceiling on breeding per plan; beyond this, hunt further afield. */
    public static final int MAX_CALVES_PER_PLAN = 4;

    /** Per-plan state: output list, cycle guard, and block bookkeeping. */
    private final class Expansion {
        private final RecipeResolver resolver;
        private final Function<String, Integer> plannedCounts;
        private final Set<String> ownedItemIds;
        private final ToIntFunction<String> liveCounts;
        private final Environment environment;
        private final List<AgentAction> plan = new ArrayList<>();
        private final Set<String> visiting = new HashSet<>();
        /** Output already promised by earlier steps of this same plan, so
         * sibling branches never re-gather shared intermediates. */
        private final Map<String, Integer> produced = new HashMap<>();
        /** Items whose tool gate is currently being satisfied up-tree,
         * guarding against circular tool requirements. */
        private final Set<String> toolChains = new HashSet<>();
        /** Blocks this plan itself placed, in order (dedups placement). */
        private final Set<String> placedBlocks = new LinkedHashSet<>();
        /** Every item id this expansion asked for, at any depth. */
        private Set<String> demanded = new LinkedHashSet<>();

        private Expansion(RecipeResolver resolver, Function<String, Integer> plannedCounts,
                          Set<String> ownedItemIds, ToIntFunction<String> liveCounts,
                          Environment environment) {
            this.resolver = resolver;
            this.plannedCounts = plannedCounts;
            this.ownedItemIds = ownedItemIds;
            this.liveCounts = liveCounts;
            this.environment = environment;
        }

        private void expand(String itemId, int needed) {
            expand(itemId, needed, false);
        }

        private void expand(String itemId, int needed, boolean isTopLevel) {
            demanded.add(itemId);
            int have = Math.max(plannedCounts.apply(itemId), 0)
                    + (isTopLevel ? 0 : produced.getOrDefault(itemId, 0));
            int missing = needed - have;
            if (missing <= 0) {
                return; // already satisfied; PRD: credit existing inventory first
            }
            String sourceBlock = DirectAcquisitions.sourceBlockFor(itemId).orElse(null);
            if (sourceBlock != null) {
                // Obsidian barely generates naturally, so searching for it is
                // mostly walking. When lava is in range, make it the way
                // players do - water on lava - then mine what we created.
                if (OBSIDIAN_ITEM.equals(itemId)
                        && environment.fluids().nearest(MakeObsidianAction.LAVA,
                                MakeObsidianAction.SEARCH_RADIUS).isPresent()) {
                    expand(BUCKET_ITEM, 1, false); // no bucket, no obsidian
                    plan.add(new MakeObsidianAction(missing,
                            environment.obsidianBlockCount(), environment.fluids(),
                            baritoneIntegration, environment.distanceSensor()));
                    com.bhautik.mcagent.McAgent.LOGGER.info(
                            "[Planner] Obsidian will be made from lava (x{})", missing);
                }
                Set<String> ownedOrPlanned = itemsOwnedOrPlanned();
                String toolReason = DirectAcquisitions.missingToolReason(itemId, ownedOrPlanned);
                if (toolReason != null) {
                    planMissingTool(itemId, toolReason);
                }
                // Biome-locked sources require standing in their biome
                // first; exploration is just another dependency step.
                DirectAcquisitions.requiredBiomeFor(itemId).ifPresent(required -> {
                    if (!required.equals(environment.biomeSensor().current())) {
                        plan.add(new com.bhautik.mcagent.action.ExploreAction(required,
                                environment.anchor().x(), environment.anchor().z(),
                                environment.biomeSensor()::current, baritoneIntegration));
                    }
                });
                // Hold the right tier while mining: wrong-tool breaks
                // destroy gated ores without dropping anything.
                String toolToHold = DirectAcquisitions
                        .qualifyingToolFor(itemId, itemsOwnedOrPlanned())
                        .orElse(null);
                String vein = sourceBlock;
                plan.add(new MineAction(sourceBlock, have, have + missing,
                        () -> liveCounts.applyAsInt(itemId), baritoneIntegration,
                        toolToHold,
                        environment.anchor(),
                        environment.equipper(),
                        // Finish the vein we walked to instead of leaving
                        // exposed ore behind for another trip.
                        () -> environment.nearbyBlockCount().apply(vein)));
                produced.merge(itemId, missing, Integer::sum);
                return;
            }
            // Mob drops (leather, raw meat): hunt rather than mine. Tried
            // before smelting/crafting so raw meat hunts and cooked meat
            // still resolves through the furnace route above it.
            var hunt = MobDrops.huntFor(itemId).orElse(null);
            if (hunt != null) {
                int kills = ceilDiv(missing, Math.max(hunt.dropsPerKill(), 1));
                // Grow the herd before harvesting it when the breeding food
                // is already carried: hunting alone strips the local
                // population and pushes every later hunt further out.
                MobDrops.breedingFoodFor(hunt.mobId()).ifPresent(food -> {
                    if (Math.max(plannedCounts.apply(food), 0) >= BreedAction.PAIR) {
                        plan.add(new BreedAction(hunt.mobId(), food,
                                calvesFor(kills), environment.hunter(),
                                environment.breeder(), baritoneIntegration));
                    }
                });
                plan.add(new HuntAction(hunt.mobId(), itemId, missing,
                        () -> liveCounts.applyAsInt(itemId),
                        environment.hunter(), baritoneIntegration,
                        environment.anchor()));
                com.bhautik.mcagent.McAgent.LOGGER.info(
                        "[Planner] Hunt planned: {} x{} (~{} kills)",
                        itemId, missing, kills);
                produced.merge(itemId, missing, Integer::sum);
                return;
            }
            if (!visiting.add(itemId)) {
                throw new PlanningException("circular dependency on " + itemId);
            }
            var smeltable = environment.smeltingResolver().findSmelting(itemId).orElse(null);
            if (smeltable != null) {
                expandSmelting(itemId, missing, smeltable);
                visiting.remove(itemId);
                produced.merge(itemId, missing, Integer::sum);
                return;
            }
            RecipeResolver.CraftableRecipe recipe = resolver.findRecipe(itemId)
                    .orElseThrow(() -> new PlanningException(
                            "no supported acquisition strategy for " + itemId));
            expandCrafting(itemId, missing, recipe);
            visiting.remove(itemId);
            produced.merge(itemId, ceilDiv(missing, recipe.resultCount())
                    * recipe.resultCount(), Integer::sum);
        }

        /** Furnace route: gather input and fuel FIRST, then secure the
         * furnace — placement must happen after every mining step, or
         * Baritone will dig through the freshly placed block on its way
         * to ore veins. */
        private void expandSmelting(String itemId, int missing,
                                    SmeltingResolver.SmeltableRecipe smeltable) {
            String input = pickResolvableInput(smeltable);
            expand(input, missing, false);
            expand(FUEL_ITEM, ceilDiv(missing, SmeltAction.ITEMS_PER_FUEL), false);
            planBlockAccess(FURNACE_ITEM, environment.furnaceLocator());
            BooleanSupplier furnaceGate = environment.furnaceLocator()::isNearby;
            plan.add(new SmeltAction(input, FUEL_ITEM, missing,
                    () -> liveCounts.applyAsInt(itemId),
                    environment.smelter(), furnaceGate));
        }

        private String pickResolvableInput(SmeltingResolver.SmeltableRecipe smeltable) {
            for (String candidate : smeltable.candidateInputItemIds()) {
                if (DirectAcquisitions.sourceBlockFor(candidate).isPresent()
                        || resolver.findRecipe(candidate).isPresent()
                        || environment.smeltingResolver().findSmelting(candidate).isPresent()) {
                    return candidate;
                }
            }
            return smeltable.representativeInput();
        }

        /** Grid route: aggregate ingredient slots, secure a table, craft. */
        private void expandCrafting(String itemId, int missing,
                                    RecipeResolver.CraftableRecipe recipe) {
            int crafts = ceilDiv(missing, recipe.resultCount());
            // Aggregate identical ingredient slots first (UC-09: shared
            // dependencies are deduplicated, e.g. 4 plank slots -> one demand).
            Map<String, Integer> demands = new LinkedHashMap<>();
            for (RecipeResolver.SlotSpec cell : recipe.cells()) {
                if (cell.isEmpty()) {
                    continue;
                }
                String representative = cell.candidateItemIds().get(0);
                demands.merge(representative, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> demand : demands.entrySet()) {
                expand(demand.getKey(), demand.getValue() * crafts, false);
            }
            if (recipe.requiresTable()) {
                planBlockAccess(CRAFTING_TABLE_ITEM, environment.tableLocator());
            }
            BooleanSupplier tableGate = recipe.requiresTable()
                    ? environment.tableLocator()::isNearby
                    : null;
            plan.add(new CraftAction(recipe, crafts, () -> liveCounts.applyAsInt(itemId),
                    environment.crafter(), tableGate));
        }

        /**
         * Guarantees the plan reaches a utility block before any gated
         * step: walk to an existing one when possible, otherwise acquire
         * the item and place it — at most one placement per block per
         * plan. Walks may repeat; arrival checks make repeats free.
         */
        /**
         * Secures a permanent enchanting table. Prefers one already in
         * reach, then any standing nearby, and otherwise crafts one and
         * places it at the home base so future enchants just walk back.
         * Unlike crafting tables and furnaces it is never added to
         * {@code placedBlocks}, so it is not collected afterwards.
         */
        private void planEnchantingSetup(BlockLocator.BlockSite homeSite) {
            BlockLocator locator = environment.enchantTableLocator();
            if (locator.isNearby()) {
                return;
            }
            var standing = locator.nearestWithin(BLOCK_SEARCH_RADIUS);
            if (standing.isPresent()) {
                walkTo(ENCHANTING_TABLE_ITEM,
                        locator.approachFor(standing.get()).orElse(standing.get()));
                return;
            }
            expand(ENCHANTING_TABLE_ITEM, 1, false);
            if (homeSite != null) {
                walkTo("base", homeSite);
            }
            plan.add(new PlaceBlockAction(ENCHANTING_TABLE_ITEM,
                    environment.placer(), locator));
        }

        /** Walks to a block-adjacent spot and verifies arrival by distance. */
        private void walkTo(String label, BlockLocator.BlockSite site) {
            plan.add(new MoveAction(label, site.x(), site.y(), site.z(),
                    BLOCK_ARRIVE_DISTANCE_SQ,
                    () -> environment.distanceSensor()
                            .distanceSquaredTo(site.x(), site.y(), site.z()),
                    baritoneIntegration));
        }

        private void planBlockAccess(String blockItemId, BlockLocator locator) {
            if (locator.isNearby()) {
                return;
            }
            locator.nearestWithin(BLOCK_SEARCH_RADIUS).ifPresentOrElse(
                    site -> {
                        // Walk to a spot BESIDE the block: targeting the
                        // block itself makes navigation mine it on arrival.
                        BlockLocator.BlockSite approach =
                                locator.approachFor(site).orElse(site);
                        plan.add(new MoveAction(blockItemId,
                                approach.x(), approach.y(), approach.z(),
                                BLOCK_ARRIVE_DISTANCE_SQ,
                                () -> environment.distanceSensor()
                                        .distanceSquaredTo(approach.x(), approach.y(),
                                                approach.z()),
                                baritoneIntegration));
                    },
                    () -> {
                        expand(blockItemId, 1, false);
                        if (placedBlocks.add(blockItemId)) {
                            plan.add(new PlaceBlockAction(blockItemId,
                                    environment.placer(), locator));
                        }
                    });
        }

        /**
         * A gated block needs a tool the agent neither owns nor plans to
         * own: fold the cheapest qualifying tool's full dependency chain
         * into this plan (craft it before mining), or refuse honestly.
         */
        private void planMissingTool(String itemId, String toolReason) {
            String tool = DirectAcquisitions.simplestToolFor(itemId)
                    .orElseThrow(() -> new PlanningException(toolReason));
            if (!toolChains.add(itemId)) {
                throw new PlanningException("circular tool requirement for " + itemId);
            }
            try {
                expand(tool, 1, false);
            } catch (PlanningException unobtainableTool) {
                throw new PlanningException(shortName(itemId) + " " + toolReason
                        + "; cannot plan a " + shortName(tool) + ": "
                        + unobtainableTool.getMessage());
            } finally {
                toolChains.remove(itemId);
            }
            String afterPlanning = DirectAcquisitions.missingToolReason(
                    itemId, itemsOwnedOrPlanned());
            if (afterPlanning != null) {
                throw new PlanningException(afterPlanning);
            }
        }

        /** Inventory ids plus everything earlier steps of this plan produce. */
        private Set<String> itemsOwnedOrPlanned() {
            Set<String> ownedOrPlanned = new HashSet<>(ownedItemIds);
            ownedOrPlanned.addAll(produced.keySet());
            return ownedOrPlanned;
        }

        private static String shortName(String itemId) {
            return itemId.replaceFirst("^minecraft:", "");
        }

        private static int ceilDiv(int dividend, int divisor) {
            return (dividend + divisor - 1) / divisor;
        }
    }
}
