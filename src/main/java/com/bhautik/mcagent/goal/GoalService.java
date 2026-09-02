package com.bhautik.mcagent.goal;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.action.ActionStatus;
import com.bhautik.mcagent.action.AgentAction;
import com.bhautik.mcagent.action.RecoverAction;
import com.bhautik.mcagent.crafting.VanillaCraftingExecutor;
import com.bhautik.mcagent.crafting.VanillaRecipeResolver;
import com.bhautik.mcagent.executor.AgentExecutor;
import com.bhautik.mcagent.integration.VanillaSurvivalMonitor;
import com.bhautik.mcagent.state.InventoryState;
import com.bhautik.mcagent.survival.Threat;
import com.bhautik.mcagent.world.BlockLocator;
import dev.minecraftai.agent.goal.AgentGoal;
import dev.minecraftai.agent.goal.AgentGoalManager;
import dev.minecraftai.agent.goal.BuildGoal;
import dev.minecraftai.agent.goal.EnchantGoal;
import dev.minecraftai.agent.goal.ExploreGoal;
import dev.minecraftai.agent.goal.GetKitGoal;
import dev.minecraftai.agent.goal.GetItemGoal;
import dev.minecraftai.agent.goal.GoalStatus;
import dev.minecraftai.agent.item.ItemRegistry;
import dev.minecraftai.agent.item.MinecraftItem;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Agent brain for get-item goals: registers goals, plans acquisition
 * actions, drives them from server ticks, verifies results against the
 * real inventory, and recovers from failures.
 */
public final class GoalService {
    private static final int PROGRESS_REFRESH_INTERVAL_TICKS = 20;
    private static final int MAX_PLAN_ATTEMPTS = 3;
    /**
     * How far around the agent counts as "the same vein". Small on
     * purpose: this is for ore already exposed where it is standing, not
     * a reason to go prospecting.
     */
    private static final int VEIN_SCAN_RADIUS = 6;
    private static final int SURVIVAL_CHECK_INTERVAL_TICKS = 10;
    /** Hostiles within this distance get engaged in melee (combat v0). */
    private static final double COMBAT_RANGE = 4.0;
    private static final int LIGHT_CHECK_INTERVAL_TICKS = 20;
    /** Free hotbag slots below which the agent pauses to stash junk. */
    private static final int FREE_SLOT_THRESHOLD = 3;
    /** Items auto-stashed when dumping junk at the base chest. */
    /**
     * By-products the agent sheds into the base chest when its bag fills.
     *
     * <p>Stashed, never destroyed, so including something useful costs
     * nothing but a trip to the chest. The lush-cave entries matter now
     * that obsidian work digs to lava depth: tunnelling through a lush
     * cave fills the inventory with vines, moss and glow berries, and
     * anything not listed here cannot be shed at all - the bag stays
     * full and mined drops stop fitting.
     */
    private static final List<String> JUNK_ITEMS = List.of(
            "minecraft:cobblestone", "minecraft:dirt", "minecraft:granite",
            "minecraft:diorite", "minecraft:andesite", "minecraft:tuff",
            "minecraft:cobbled_deepslate", "minecraft:flint",
            "minecraft:gravel", "minecraft:sand", "minecraft:calcite",
            "minecraft:smooth_basalt", "minecraft:clay_ball",
            // Lush caves, hit on the way down to lava.
            "minecraft:glow_berries", "minecraft:hanging_roots",
            "minecraft:rooted_dirt", "minecraft:moss_block",
            "minecraft:moss_carpet", "minecraft:azalea",
            "minecraft:flowering_azalea", "minecraft:glow_lichen",
            "minecraft:small_dripleaf", "minecraft:big_dripleaf",
            "minecraft:spore_blossom", "minecraft:vine",
            "minecraft:cave_vines", "minecraft:seagrass");
    /** Chunk radius for vanilla structure searches (16 chunks = 256 blocks). */
    private static final int STRUCTURE_SEARCH_RADIUS_CHUNKS = 16;
    /** Within this distance (squared) of a located structure, we are there. */
    private static final double STRUCTURE_ARRIVE_DISTANCE_SQ = 48.0 * 48.0;
    /** Gatherable foods the agent plans for itself when starving. */
    private static final List<String> EMERGENCY_FOODS = List.of(
            "minecraft:sweet_berries", "minecraft:brown_mushroom",
            "minecraft:red_mushroom");
    private static final int EMERGENCY_FOOD_COUNT = 8;

    /** JVM-only fallback used by the CLI smoke checks; in-game resolution uses the live registry. */
    private final ItemRegistry itemRegistry = ItemRegistry.vanillaDefaults();
    private final AgentGoalManager goalManager = new AgentGoalManager();
    private final AgentExecutor executor;
    private final Object monitor = new Object();

    private ActiveRun run;

    // Home base (PRD M11 groundwork): persisted in overworld saved data,
    // so it survives server restarts.
    private net.minecraft.core.BlockPos baseAnchor(MinecraftServer server) {
        var state = com.bhautik.mcagent.integration.BaseSavedState.get(server);
        if (state == null) {
            return null;
        }
        int[] a = state.anchor();
        return state.hasChest() || a[0] != 0 || a[1] != 0 || a[2] != 0
                ? new net.minecraft.core.BlockPos(a[0], a[1], a[2]) : null;
    }

    private net.minecraft.core.BlockPos baseChestPos(MinecraftServer server) {
        var state = com.bhautik.mcagent.integration.BaseSavedState.get(server);
        int[] c = state == null ? null : state.chest();
        return c == null ? null : new net.minecraft.core.BlockPos(c[0], c[1], c[2]);
    }

    private void saveBaseChest(MinecraftServer server, net.minecraft.core.BlockPos chest) {
        var state = com.bhautik.mcagent.integration.BaseSavedState.get(server);
        var anchor = baseAnchor(server);
        if (anchor != null) {
            state.setAnchor(anchor.getX(), anchor.getY(), anchor.getZ());
        }
        state.setChest(chest.getX(), chest.getY(), chest.getZ());
    }

    private void saveBaseAnchor(MinecraftServer server, net.minecraft.core.BlockPos anchor) {
        var state = com.bhautik.mcagent.integration.BaseSavedState.get(server);
        state.setAnchor(anchor.getX(), anchor.getY(), anchor.getZ());
        var chest = baseChestPos(server);
        if (chest != null) {
            state.setChest(chest.getX(), chest.getY(), chest.getZ());
        }
    }

    public GoalService(AgentExecutor executor) {
        this.executor = executor;
    }

    public boolean isValidItem(String rawName) {
        return resolve(rawName).isPresent();
    }

    /**
     * Enchant goal: the agent self-provisions everything it can — the
     * item, lapis, and an enchanting table (walked to, or crafted and
     * placed) — then enchants and picks the table back up.
     *
     * <p>XP is the one input that cannot be planned as an item; mining
     * the dependencies earns it incidentally and the enchant step fails
     * honestly when the level is still short.
     */
    public String enchant(ServerPlayer player, String rawItemName,
                          Integer requestedLevel) {
        synchronized (monitor) {
            lastRequest = new LastRequest(RequestKind.ENCHANT, rawItemName, 1,
                    requestedLevel);
            if (goalManager.activeGoal().isPresent()) {
                return "A goal is already active. Use /agent goal to view it or /agent cancel first.";
            }
            clearStaleExecutor();
            Optional<MinecraftItem> itemOpt = resolve(rawItemName);
            if (itemOpt.isEmpty()) {
                return "Invalid item name: " + rawItemName;
            }
            String itemId = itemOpt.get().id();
            if (!isEnchantable(itemId)) {
                return "Invalid item: " + shortName(itemId) + " cannot be enchanted";
            }
            int minLevel = requestedLevel == null ? 1 : requestedLevel;
            MinecraftServer server = player.level().getServer();
            UUID playerId = player.getUUID();

            EnchantGoal goal = new EnchantGoal(shortName(itemId), minLevel,
                    () -> enchantedCountById(server, playerId, itemId) > 0,
                    () -> com.bhautik.mcagent.integration.VanillaXpSensor
                            .sensor(player).level());
            McAgent.LOGGER.info("[Agent] Goal created: {}", goal.title());
            goalManager.register(goal);
            if (goal.status() == GoalStatus.SUCCESS) {
                McAgent.LOGGER.info("[Agent] Goal completed immediately: {}", goal.title());
                return goal.progressReport();
            }
            ActiveRun activeRun = new ActiveRun(goal, itemOpt.get(), playerId, 1,
                    snapshot(player), server,
                    VanillaSurvivalMonitor.monitor(player));
            activeRun.enchantItemId = itemId;
            activeRun.enchantMinLevel = minLevel;
            run = activeRun;
            if (!replan(run)) {
                return finishWithFailure(run);
            }
            String immediate = settleImmediateFailure();
            if (immediate != null) {
                return immediate;
            }
            return goal.progressReport()
                    + System.lineSeparator() + "Agent is working. Use /agent goal for progress.";
        }
    }

    /**
     * Handles an action that finished the instant it launched.
     *
     * <p>A best-effort step failing here must NOT sink the goal - it is
     * optional by definition, and the plan carries a real alternative
     * behind it. Breeding failing with no cows around took whole enchant
     * runs down before this distinction existed.
     *
     * @return the goal report when the run really is over, else null
     */
    private String settleImmediateFailure() {
        var finished = executor.pollFinished();
        while (finished.isPresent()) {
            AgentAction action = finished.get();
            if (!action.bestEffort()) {
                ActiveRun failedRun = run;
                run = null;
                failedRun.goal.markFailed(action.failureReason());
                McAgent.LOGGER.warn("[Agent] Goal failed immediately: {} ({})",
                        failedRun.goal.title(), failedRun.goal.failureReason());
                return failedRun.goal.progressReport();
            }
            McAgent.LOGGER.info("[Recovery] Optional step failed at launch; skipping: {} ({})",
                    action.title(), action.failureReason());
            if (!launchNextAction()) {
                break; // the serverTick stall guard closes this out
            }
            finished = executor.pollFinished();
        }
        return null;
    }

    /** Blueprint names the agent can build: built-ins plus files on disk. */
    public Set<String> blueprintNames() {
        var names = new java.util.TreeSet<>(
                com.bhautik.mcagent.build.Blueprints.names());
        names.addAll(com.bhautik.mcagent.integration.NbtBlueprintLoader.available());
        return names;
    }

    /**
     * Build goal: take off the blueprint's materials, gather what is
     * missing, walk to the site and raise it. A structure file on disk
     * wins over a built-in of the same name, so designs can be replaced
     * without touching code.
     */
    public String build(ServerPlayer player, String rawName) {
        synchronized (monitor) {
            lastRequest = new LastRequest(RequestKind.BUILD, rawName, 1, null);
            if (goalManager.activeGoal().isPresent()) {
                return "A goal is already active. Use /agent goal to view it or /agent cancel first.";
            }
            clearStaleExecutor();
            var blueprint = com.bhautik.mcagent.integration.NbtBlueprintLoader
                    .load(rawName)
                    .or(() -> com.bhautik.mcagent.build.Blueprints.byName(rawName))
                    .orElse(null);
            if (blueprint == null) {
                return "Invalid blueprint: " + rawName + " (known: "
                        + blueprintNames() + ")";
            }
            MinecraftServer server = player.level().getServer();
            UUID playerId = player.getUUID();
            var site = buildSiteFor(server, player, blueprint);
            if (site == null) {
                return "Invalid site: no clear "
                        + blueprint.width() + "x" + blueprint.length()
                        + " ground within "
                        + com.bhautik.mcagent.integration.BuildSiteFinder.MAX_RANGE
                        + " blocks of base that is free of other structures";
            }

            BuildGoal goal = new BuildGoal(blueprint.describe(),
                    () -> false, // verified by the build action against the world
                    () -> blueprint.blockCount());
            McAgent.LOGGER.info("[Agent] Goal created: {}", goal.title());
            McAgent.LOGGER.info("[Build] Materials: {}", blueprint.materials());
            goalManager.register(goal);
            ActiveRun activeRun = new ActiveRun(goal, null, playerId, 1,
                    snapshot(player), server, VanillaSurvivalMonitor.monitor(player));
            activeRun.blueprint = blueprint;
            activeRun.buildSite = site;
            run = activeRun;
            if (!replan(run)) {
                return finishWithFailure(run);
            }
            var started = executor.pollFinished();
            if (started.isPresent()) {
                ActiveRun failedRun = run;
                run = null;
                failedRun.goal.markFailed(started.get().failureReason());
                return failedRun.goal.progressReport();
            }
            return goal.progressReport()
                    + System.lineSeparator() + "Agent is working. Use /agent goal for progress.";
        }
    }

    /**
     * Surveys for a site near the base that actually fits the structure:
     * flat enough, clear of base furniture, and not overlapping anything
     * already built. Null when nothing suitable is in range, so the goal
     * can say so rather than bulldozing a spot.
     */
    private BlockLocator.BlockSite buildSiteFor(MinecraftServer server,
                                                ServerPlayer player,
                                                com.bhautik.mcagent.build.Blueprint blueprint) {
        if (server == null) {
            return null;
        }
        var state = com.bhautik.mcagent.integration.BaseSavedState.get(server);
        int[] anchor = state.anchor();
        var origin = (anchor[0] == 0 && anchor[1] == 0 && anchor[2] == 0)
                ? player.blockPosition()
                : new net.minecraft.core.BlockPos(anchor[0], anchor[1], anchor[2]);
        return com.bhautik.mcagent.integration.BuildSiteFinder
                .find(player, blueprint, origin, state.reservations())
                .orElse(null);
    }

    private static String shortName(String itemId) {
        return itemId.replaceFirst("^minecraft:", "");
    }

    /** True when vanilla would accept the item in an enchanting table. */
    private boolean isEnchantable(String itemId) {
        var id = Identifier.tryParse(itemId);
        if (id == null) {
            return false;
        }
        var stack = BuiltInRegistries.ITEM.getOptional(id)
                .map(net.minecraft.world.item.ItemStack::new)
                .orElse(null);
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        // Books are not "enchantable" as tools but are valid table input.
        return stack.isEnchantable() || itemId.equals("minecraft:book");
    }

    /**
     * Resolves shorthand ("diamond") or namespaced ids against the live
     * vanilla item registry, so every real item is requestable.
     */
    private Optional<MinecraftItem> resolve(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }
        String normalized = rawName.trim().toLowerCase();
        String qualified = normalized.contains(":") ? normalized : "minecraft:" + normalized;
        Identifier id = Identifier.tryParse(qualified);
        if (id == null) {
            return Optional.empty();
        }
        boolean exists = BuiltInRegistries.ITEM.get(id).isPresent();
        return exists ? Optional.of(new MinecraftItem(id.toString())) : Optional.empty();
    }

    public String getItem(ServerPlayer player, String rawItemName, int requestedCount) {
        synchronized (monitor) {
            lastRequest = new LastRequest(RequestKind.GET, rawItemName,
                    requestedCount, null);
            if (goalManager.activeGoal().isPresent()) {
                return "A goal is already active. Use /agent goal to view it or /agent cancel first.";
            }
            clearStaleExecutor();
            Optional<MinecraftItem> itemOpt = resolve(rawItemName);
            dev.minecraftai.agent.world.InventoryState snapshot = snapshot(player);
            ActiveRun activeRun;
            AgentGoal goal;
            if (itemOpt.isPresent()) {
                GetItemGoal itemGoal = new GetItemGoal(itemOpt.get(), requestedCount, snapshot);
                goal = itemGoal;
                McAgent.LOGGER.info("[Agent] Goal created: {}", goal.title());
                goalManager.register(goal);
                if (goal.status() == GoalStatus.SUCCESS) {
                    McAgent.LOGGER.info("[Agent] Goal completed immediately: {}", goal.title());
                    return goal.progressReport();
                }
                activeRun = new ActiveRun(itemGoal, itemOpt.get(), player.getUUID(),
                        requestedCount, snapshot, player.level().getServer(),
                        com.bhautik.mcagent.integration.VanillaSurvivalMonitor.monitor(player));
            } else {
                List<MinecraftItem> pieces = com.bhautik.mcagent.item.Kits
                        .itemsFor(rawItemName)
                        .orElseThrow(() -> new IllegalArgumentException("invalid item or kit"))
                        .stream().map(MinecraftItem::new).toList();
                GetKitGoal kitGoal = new GetKitGoal(rawItemName.trim().toLowerCase(),
                        pieces, requestedCount,
                        () -> pieces.stream().allMatch(piece ->
                                liveCountById(player.level().getServer(), player.getUUID(),
                                        piece.id()) >= requestedCount),
                        piece -> liveCountById(player.level().getServer(), player.getUUID(),
                                piece.id()));
                goal = kitGoal;
                McAgent.LOGGER.info("[Agent] Goal created: {}", goal.title());
                goalManager.register(goal);
                if (goal.status() == GoalStatus.SUCCESS) {
                    McAgent.LOGGER.info("[Agent] Goal completed immediately: {}", goal.title());
                    return goal.progressReport();
                }
                activeRun = new ActiveRun(kitGoal, null, player.getUUID(), requestedCount,
                        snapshot, player.level().getServer(),
                        com.bhautik.mcagent.integration.VanillaSurvivalMonitor.monitor(player));
                activeRun.kitItems = pieces;
            }
            run = activeRun;
            if (!replan(run)) {
                return finishWithFailure(run);
            }
            // A missing navigation backend fails synchronously; surface it now
            // instead of promising work that already ended.
            String immediate = settleImmediateFailure();
            if (immediate != null) {
                return immediate;
            }
            return goal.progressReport()
                    + System.lineSeparator() + "Agent is working. Use /agent goal for progress.";
        }
    }

    public String describeActiveGoal() {
        synchronized (monitor) {
            return goalManager.describeActiveGoal();
        }
    }

    /** True when the named biome exists in the given world's registry. */
    public boolean isValidBiome(net.minecraft.world.level.Level level, String rawName) {
        return resolveBiome(level, rawName).isPresent();
    }

    private Optional<String> resolveBiome(net.minecraft.world.level.Level level,
                                          String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }
        String normalized = rawName.trim().toLowerCase();
        String qualified = normalized.contains(":") ? normalized : "minecraft:" + normalized;
        net.minecraft.resources.Identifier id = Identifier.tryParse(qualified);
        if (id == null) {
            return Optional.empty();
        }
        boolean exists = level.registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                .getOptional(id).isPresent();
        return exists ? Optional.of(qualified) : Optional.empty();
    }

    /** UC-08: travel to and verify a named biome. */
    public String explore(ServerPlayer player, String rawBiomeName) {
        synchronized (monitor) {
            if (goalManager.activeGoal().isPresent()) {
                return "A goal is already active. Use /agent goal to view it or /agent cancel first.";
            }
            clearStaleExecutor();
            String targetBiome = resolveBiome(player.level(), rawBiomeName)
                    .orElseThrow(() -> new IllegalArgumentException("invalid biome"));
            com.bhautik.mcagent.world.BiomeSensor biomeAt = () ->
                    player.level().getBiome(player.blockPosition()).unwrapKey()
                            .map(key -> key.identifier().toString()).orElse("");
            ExploreGoal goal = new ExploreGoal(
                    rawBiomeName.trim().toLowerCase(),
                    () -> targetBiome.equals(biomeAt.current()));
            McAgent.LOGGER.info("[Agent] Goal created: {}", goal.title());
            goalManager.register(goal);
            if (goal.status() == GoalStatus.SUCCESS) {
                McAgent.LOGGER.info("[Agent] Goal completed immediately: {}", goal.title());
                return goal.progressReport();
            }
            ActiveRun activeRun = new ActiveRun(goal, null, player.getUUID(), 0,
                    snapshot(player), player.level().getServer(),
                    com.bhautik.mcagent.integration.VanillaSurvivalMonitor.monitor(player));
            activeRun.exploreTargetBiome = targetBiome;
            activeRun.biomeAt = biomeAt;
            run = activeRun;
            if (!replan(run)) {
                return finishWithFailure(run);
            }
            String immediate = settleImmediateFailure();
            if (immediate != null) {
                return immediate;
            }
            return goal.progressReport()
                    + System.lineSeparator() + "Agent is exploring. Use /agent goal for progress.";
        }
    }

    /** M9: locate the nearest tagged structure and travel to it. */
    public String exploreStructure(ServerPlayer player, String rawName) {
        synchronized (monitor) {
            if (goalManager.activeGoal().isPresent()) {
                return "A goal is already active. Use /agent goal to view it or /agent cancel first.";
            }
            clearStaleExecutor();
            String tagId = com.bhautik.mcagent.world.StructureDirectory.tagFor(rawName)
                    .orElseThrow(() -> new IllegalArgumentException("unknown structure"));
            var level = (net.minecraft.server.level.ServerLevel) player.level();
            var tag = net.minecraft.tags.TagKey.create(
                    net.minecraft.core.registries.Registries.STRUCTURE,
                    Identifier.parse("minecraft:" + tagId));
            net.minecraft.core.BlockPos found = level.findNearestMapStructure(
                    tag, player.blockPosition(), STRUCTURE_SEARCH_RADIUS_CHUNKS, false);
            if (found == null) {
                return "No " + rawName + " found within "
                        + (STRUCTURE_SEARCH_RADIUS_CHUNKS * 16) + " blocks of here.";
            }
            net.minecraft.core.BlockPos target = found.immutable();
            McAgent.LOGGER.info("[Agent] Located {} at {} {} {}", rawName,
                    target.getX(), target.getY(), target.getZ());
            java.util.function.DoubleSupplier distance =
                    () -> player.distanceToSqr(target.getX(), target.getY(), target.getZ());
            ExploreGoal goal = new ExploreGoal(rawName.trim().toLowerCase(),
                    () -> distance.getAsDouble() <= STRUCTURE_ARRIVE_DISTANCE_SQ);
            McAgent.LOGGER.info("[Agent] Goal created: {}", goal.title());
            goalManager.register(goal);
            ActiveRun activeRun = new ActiveRun(goal, null, player.getUUID(), 0,
                    snapshot(player), player.level().getServer(),
                    com.bhautik.mcagent.integration.VanillaSurvivalMonitor.monitor(player));
            activeRun.structureTargetPos = target;
            activeRun.structureDistance = distance;
            run = activeRun;
            if (!replan(run)) {
                return finishWithFailure(run);
            }
            var started = executor.pollFinished();
            if (started.isPresent()) {
                ActiveRun failedRun = run;
                run = null;
                failedRun.goal.markFailed(started.get().failureReason());
                return failedRun.goal.progressReport();
            }
            return goal.progressReport() + System.lineSeparator()
                    + "Heading to " + rawName + " at " + target.getX() + " "
                    + target.getY() + " " + target.getZ() + ".";
        }
    }

    /** Establishes (or reports) the home base at the player's position. */
    public String base(ServerPlayer player) {
        synchronized (monitor) {
            if (goalManager.activeGoal().isPresent()) {
                return "A goal is already active. Use /agent cancel first.";
            }
            var pos = player.blockPosition().immutable();
            StringBuilder report = new StringBuilder("Base set at "
                    + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                    + " (persisted)");
            var placer = com.bhautik.mcagent.integration.VanillaPlacementExecutor
                    .placer(player);
            var existingChest = baseChestPos(player.level().getServer());
            if (existingChest != null) {
                saveBaseChest(player.level().getServer(), existingChest);
                report.append("\nBase chest already at ").append(existingChest.getX())
                        .append(" ").append(existingChest.getY()).append(" ")
                        .append(existingChest.getZ());
            } else {
                var adopted = com.bhautik.mcagent.integration.VanillaStorage
                        .findStoragePos(player, 32);
                McAgent.LOGGER.info("[Agent] Storage scan within 32 blocks: {}",
                        adopted == null ? "none found" : adopted.toShortString());
                if (adopted != null) {
                    saveBaseChest(player.level().getServer(), adopted);
                    report.append("\nAdopted nearby storage at ").append(adopted.getX())
                            .append(" ").append(adopted.getY()).append(" ")
                            .append(adopted.getZ());
                } else {
                    var chestResult = placer.place(
                            com.bhautik.mcagent.planner.Planner.BASE_CHEST_ITEM);
                    if (chestResult.success()) {
                        saveBaseChest(player.level().getServer(), player.blockPosition());
                        report.append("\nPlaced base chest");
                    } else {
                        report.append("\nNo chest placed: ")
                                .append(chestResult.failureReason())
                                .append(" - stand next to your chest and retry,")
                                .append(" or craft one with /agent get chest 1");
                    }
                }
            }
            saveBaseAnchor(player.level().getServer(), pos);
            McAgent.LOGGER.info("[Agent] Base established at {} {} {}",
                    pos.getX(), pos.getY(), pos.getZ());
            return report.toString().replace("\n", " | ");
        }
    }

    /** UC-09 groundwork: store items at the base chest. */
    public String stash(ServerPlayer player, String itemArg, String amountArg) {
        synchronized (monitor) {
            if (goalManager.activeGoal().isPresent()) {
                return "A goal is already active. Use /agent cancel first.";
            }
            if (baseChestPos(player.level().getServer()) == null) {
                return "No base yet. Run /agent base here first.";
            }
            List<String> ids;
            boolean dumpingJunk = itemArg.equals("*") || itemArg.equalsIgnoreCase("junk");
            if (dumpingJunk) {
                ids = JUNK_ITEMS;
            } else {
                Optional<MinecraftItem> resolved =
                        resolve(itemArg).or(() -> resolve("minecraft:" + itemArg));
                if (resolved.isEmpty()) {
                    return "Invalid item name: " + itemArg;
                }
                ids = List.of(resolved.get().id());
            }
            int cap;
            if (amountArg == null || amountArg.isBlank()
                    || amountArg.equalsIgnoreCase("all")) {
                cap = Integer.MAX_VALUE;
            } else {
                try {
                    cap = Integer.parseInt(amountArg);
                } catch (NumberFormatException badCount) {
                    return "Invalid count: " + amountArg;
                }
            }
            ExploreGoal goal = new ExploreGoal("stash " + itemArg, () -> false);
            McAgent.LOGGER.info("[Agent] Goal created: {}", goal.title());
            goalManager.register(goal);
            ActiveRun activeRun = new ActiveRun(goal, null, player.getUUID(), 0,
                    snapshot(player), player.level().getServer(),
                    com.bhautik.mcagent.integration.VanillaSurvivalMonitor.monitor(player));
            activeRun.stashIds = ids;
            run = activeRun;
            if (!replan(run)) {
                return finishWithFailure(run);
            }
            return goal.progressReport() + System.lineSeparator()
                    + "Storing " + String.join(", ", ids.size() > 3
                            ? ids.subList(0, 3) : ids)
                    + (ids.size() > 3 ? ", ..." : "") + " at base.";
        }
    }

    /** Pull supplies back out of the base chest (torches, food, items). */
    public String restock(ServerPlayer player, String nameArg, int count) {
        synchronized (monitor) {
            if (goalManager.activeGoal().isPresent()) {
                return "A goal is already active. Use /agent cancel first.";
            }
            var chest = baseChestPos(player.level().getServer());
            if (chest == null) {
                return "No base yet. Run /agent base here first.";
            }
            List<String> ids;
            int maxStacks;
            switch (nameArg.trim().toLowerCase()) {
                case "torches" -> {
                    ids = List.of(com.bhautik.mcagent.planner.Planner.TORCH_ITEM);
                    maxStacks = 2;
                }
                case "food" -> {
                    ids = com.bhautik.mcagent.integration.VanillaStorage.foodIdsInChest(
                            player,
                            com.bhautik.mcagent.integration.VanillaPlacementExecutor.INTERACTION_RADIUS);
                    if (ids.isEmpty()) {
                        return "No food stored in the base chest.";
                    }
                    maxStacks = 2;
                }
                default -> {
                    Optional<MinecraftItem> resolved =
                            resolve(nameArg).or(() -> resolve("minecraft:" + nameArg));
                    if (resolved.isEmpty()) {
                        return "Invalid item name: " + nameArg
                                + " (presets: torches, food)";
                    }
                    ids = List.of(resolved.get().id());
                    maxStacks = Math.max(1, Math.min(3, (count + 63) / 64));
                }
            }
            ExploreGoal goal = new ExploreGoal("restock " + nameArg, () -> false);
            McAgent.LOGGER.info("[Agent] Goal created: {}", goal.title());
            goalManager.register(goal);
            ActiveRun activeRun = new ActiveRun(goal, null, player.getUUID(), 0,
                    snapshot(player), player.level().getServer(),
                    com.bhautik.mcagent.integration.VanillaSurvivalMonitor.monitor(player));
            activeRun.restockIds = ids;
            activeRun.restockMaxStacks = maxStacks;
            run = activeRun;
            if (!replan(run)) {
                return finishWithFailure(run);
            }
            return goal.progressReport() + System.lineSeparator()
                    + "Restocking from base chest.";
        }
    }

    public String returnToBase(ServerPlayer player) {
        synchronized (monitor) {
            if (goalManager.activeGoal().isPresent()) {
                return "A goal is already active. Use /agent cancel first.";
            }
            var anchor = baseAnchor(player.level().getServer());
            if (anchor == null) {
                return "No base yet. Run /agent base here first.";
            }
            ExploreGoal goal = new ExploreGoal("return to base",
                    () -> player.blockPosition().distSqr(anchor) <= 16.0);
            McAgent.LOGGER.info("[Agent] Goal created: {}", goal.title());
            goalManager.register(goal);
            if (goal.status() == GoalStatus.SUCCESS) {
                return goal.progressReport();
            }
            ActiveRun activeRun = new ActiveRun(goal, null, player.getUUID(), 0,
                    snapshot(player), player.level().getServer(),
                    com.bhautik.mcagent.integration.VanillaSurvivalMonitor.monitor(player));
            // Reuse structure distance pathway for generic return
            activeRun.structureTargetPos = anchor;
            activeRun.structureDistance = () -> player.distanceToSqr(
                    anchor.getX(), anchor.getY(), anchor.getZ());
            run = activeRun;
            if (!replan(run)) {
                return finishWithFailure(run);
            }
            return goal.progressReport() + System.lineSeparator()
                    + "Returning to base at " + anchor.getX() + " " + anchor.getY() + " " + anchor.getZ() + ".";
        }
    }

    /** What the player last asked for, so it can be picked up again. */
    private enum RequestKind { GET, ENCHANT, BUILD }

    private record LastRequest(RequestKind kind, String arg, int count,
                               Integer level) {
    }

    private LastRequest lastRequest;

    /**
     * Picks the last goal back up.
     *
     * <p>Re-planning IS resuming: the planner credits whatever the agent
     * already carries or has stored, so a re-issued goal only does the
     * work that is still outstanding. That also makes this the way to
     * unstick a run whose action died quietly - it replans from reality
     * rather than from where the old plan thought it was.
     */
    public String resume(ServerPlayer player) {
        synchronized (monitor) {
            ActiveRun activeRun = run;
            if (activeRun != null && goalManager.activeGoal().isPresent()) {
                // Still live: replan in place rather than starting over,
                // so progress and attempt count survive.
                activeRun.attempts = 0;
                // Drop whatever is running first. This is the unstick
                // command, and the executor refuses to launch while busy -
                // so without this, resuming a wedged action just fails the
                // goal it was meant to rescue.
                if (executor.busy()) {
                    executor.cancelCurrent("resuming goal");
                }
                if (!replan(activeRun)) {
                    return finishWithFailure(activeRun);
                }
                McAgent.LOGGER.info("[Agent] Goal resumed: {}", activeRun.goal.title());
                return "Resumed: " + activeRun.goal.title();
            }
            if (lastRequest == null) {
                return "No previous goal to resume.";
            }
            LastRequest again = lastRequest;
            McAgent.LOGGER.info("[Agent] Re-issuing last goal: {} {}",
                    again.kind(), again.arg());
            return switch (again.kind()) {
                case GET -> getItem(player, again.arg(), again.count());
                case ENCHANT -> enchant(player, again.arg(), again.level());
                case BUILD -> build(player, again.arg());
            };
        }
    }

    public String cancelActiveGoal() {
        synchronized (monitor) {
            if (run != null) {
                executor.cancelCurrent("user requested cancellation");
                run = null;
            }
            String report = goalManager.cancelActiveGoal();
            McAgent.LOGGER.info("[Agent] Goal cancelled: {}", report.replace("\n", " "));
            return report;
        }
    }

    /** Drives the active run from END_SERVER_TICK. Cheap no-op when idle. */
    public void serverTick(MinecraftServer server) {
        synchronized (monitor) {
            if (run == null) {
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(run.playerId);
            if (player == null) {
                abandonRun("player left the server");
                return;
            }
            if (player.isDeadOrDying()) {
                McAgent.LOGGER.warn("[Agent] Agent died at {} {} {}",
                        player.blockPosition().getX(), player.blockPosition().getY(),
                        player.blockPosition().getZ());
                executor.cancelCurrent("agent died");
                if (run != null) {
                    run.goal.markFailed("agent died at "
                            + player.blockPosition().toShortString() + " - loot dropped");
                    McAgent.LOGGER.warn("[Agent] Goal failed: {} ({})",
                            run.goal.title(), run.goal.failureReason());
                    run = null;
                }
                return;
            }

            run.tickCount++;
            if (run.tickCount % SURVIVAL_CHECK_INTERVAL_TICKS == 0) {
                // Combat first: swinging at a creeper outranks stashing.
                boolean engaged = com.bhautik.mcagent.integration.VanillaCombat
                        .attacker(player,
                                com.bhautik.mcagent.integration.VanillaEquipment
                                        .equipper(player))
                        .strikeNearestHostile(COMBAT_RANGE);
                if (engaged && run.combatTarget == null) {
                    run.combatTarget = "hostile";
                    McAgent.LOGGER.warn("[Combat] Engaging hostile near agent");
                } else if (!engaged) {
                    run.combatTarget = null;
                }
                if (run != null) {
                    handleSurvival(run, player);
                    if (run != null) {
                        handleInventoryPressure(run, player);
                    }
                }
            }
            if (run != null && run.tickCount % LIGHT_CHECK_INTERVAL_TICKS == 0) {
                handleLighting(run, player);
            }
            if (run == null) {
                return;
            }
            executor.tick();

            if (run.tickCount % PROGRESS_REFRESH_INTERVAL_TICKS == 0) {
                refreshSnapshot(player);
            }

            executor.pollFinished().ifPresent(finished -> handleFinishedAction(player, finished));

            // Safety net: a run with nothing executing and nothing queued
            // will never advance, because everything here is driven by an
            // action FINISHING. Left alone it stays ACTIVE forever and
            // blocks every later command with "a goal is already active".
            if (run != null && !executor.busy() && runQueueIsEmpty(run)) {
                closeOutStalledRun(player);
            }
        }
    }

    /**
     * Ends or revives a run that has no work in flight. Tries a replan
     * first - the goal may simply need re-deriving from current state -
     * and only gives up when that produces nothing either.
     */
    private void closeOutStalledRun(ServerPlayer player) {
        ActiveRun activeRun = run;
        refreshSnapshot(player);
        int current = activeRun.item == null ? 0
                : activeRun.snapshot.count(activeRun.item);
        if (isSatisfied(activeRun, current)) {
            activeRun.goal.markSuccess();
            display(activeRun).finish("Done: " + activeRun.goal.title(), true);
            McAgent.LOGGER.info("[Agent] Goal completed: {}", activeRun.goal.title());
            run = null;
            return;
        }
        if (activeRun.attempts < MAX_PLAN_ATTEMPTS && replan(activeRun)) {
            McAgent.LOGGER.info("[Recovery] Run had stalled with an empty queue;"
                    + " replanned");
            return;
        }
        McAgent.LOGGER.warn("[Agent] Goal stalled with nothing left to run: {}",
                activeRun.goal.title());
        finishWithFailure(activeRun);
    }

    /**
     * Drops a torch whenever the agent stands in the dark during any
     * active goal - not just while mining. Best-effort, no queue.
     */
    private void handleLighting(ActiveRun run, ServerPlayer player) {
        if (player.level().getMaxLocalRawBrightness(player.blockPosition())
                >= com.bhautik.mcagent.action.TunnelLighter.MIN_LIGHT) {
            return;
        }
        var inv = player.getInventory();
        boolean hasTorch = false;
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            var stack = inv.getItem(slot);
            if (!stack.isEmpty()
                    && net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(stack.getItem()).toString()
                            .equals(com.bhautik.mcagent.planner.Planner.TORCH_ITEM)) {
                hasTorch = true;
                break;
            }
        }
        if (!hasTorch) {
            return;
        }
        boolean placed = com.bhautik.mcagent.integration.VanillaPlacementExecutor
                .placer(player).place(com.bhautik.mcagent.planner.Planner.TORCH_ITEM)
                .success();
        if (placed) {
            McAgent.LOGGER.info("[Action] Placed torch (light {})", player.blockPosition().toShortString());
        }
    }

    /**
     * A nearly-full bag starves the goal: target drops never get picked
     * up. When the base chest exists, detour once - pause, walk home,
     * dump the junk list, resume. Without a base the run keeps going
     * and the eventual stall explains itself honestly.
     */
    private void handleInventoryPressure(ActiveRun activeRun, ServerPlayer player) {
        if (activeRun.stashing || activeRun.recovering || activeRun.securingFood
                || activeRun.exploreTargetBiome != null || activeRun.stashIds != null
                || activeRun.stashingIsFutile
                || baseChestPos(player.level().getServer()) == null) {
            return;
        }
        var inventory = player.getInventory();
        int free = 0;
        for (int slot = 0; slot < com.bhautik.mcagent.integration.VanillaStorage
                .MAIN_INVENTORY_SIZE; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                free++;
            }
        }
        if (free > FREE_SLOT_THRESHOLD) {
            // Space has appeared since a stash came back empty, so the
            // detour is worth trying again next time it fills up.
            activeRun.stashingIsFutile = false;
            return;
        }
        McAgent.LOGGER.warn("[Recovery] Inventory almost full ({} free); stashing junk at base",
                free);
        display(activeRun).note("Bag nearly full (" + free
                + " free); detouring to stash junk");
        AgentAction suspended = executor.suspendCurrent("inventory almost full");
        if (suspended != null) {
            activeRun.queue.addFirst(suspended);
        }
        // Front-insert in reverse so travel lands before the deposit.
        java.util.List<AgentAction> detour = stashJunkDetour(activeRun, player);
        for (int i = detour.size() - 1; i >= 0; i--) {
            activeRun.queue.addFirst(detour.get(i));
        }
        activeRun.stashing = true;
        launchNextAction();
    }

    /** Walk-to-base (when far) plus a junk-list deposit step. */
    private java.util.List<AgentAction> stashJunkDetour(ActiveRun activeRun,
                                                        ServerPlayer player) {
        var chest = baseChestPos(player.level().getServer());
        java.util.List<AgentAction> detour = new ArrayList<>();
        var approach2 = com.bhautik.mcagent.integration.VanillaPlacementExecutor
                .findApproachSpot(player, chest);
        var target2 = approach2 != null ? approach2 : chest;
        java.util.function.DoubleSupplier distance = () ->
                player.distanceToSqr(target2.getX(), target2.getY(), target2.getZ());
        if (player.blockPosition().distSqr(target2) > 16.0) {
            detour.add(new com.bhautik.mcagent.action.MoveAction("base chest",
                    chest.getX(), chest.getY(), chest.getZ(),
                    4.0 * 4.0, distance::getAsDouble,
                    executor.baritoneIntegration()));
        }
        detour.add(new com.bhautik.mcagent.action.DepositAction(
                "Auto-stash junk", JUNK_ITEMS,
                com.bhautik.mcagent.integration.VanillaStorage.depositor(player,
                        com.bhautik.mcagent.integration.VanillaPlacementExecutor.INTERACTION_RADIUS)));
        return detour;
    }

    /**
     * Survival outranks the active goal (PRD 15): on emergency the
     * current action is paused and re-queued, and a recovery step jumps
     * the queue. When it succeeds, the original action relaunches fresh.
     */
    private void handleSurvival(ActiveRun activeRun, ServerPlayer player) {
        Threat threat = activeRun.survivalMonitor.assess();
        if (!threat.emergency() || activeRun.recovering || activeRun.securingFood) {
            // While gathering emergency food the interruption system
            // stands down — otherwise it would suspend the very steps
            // that fetch the food.
            return;
        }
        AgentAction suspended = executor.suspendCurrent(threat.reason());
        if (suspended != null) {
            activeRun.queue.addFirst(suspended);
        }
        AgentAction recovery = threat.needsAir()
                ? new com.bhautik.mcagent.action.SurfaceAction(activeRun.survivalMonitor,
                        VanillaSurvivalMonitor.swimmer(player))
                : new RecoverAction(activeRun.survivalMonitor,
                        VanillaSurvivalMonitor.feeder(player));
        activeRun.queue.addFirst(recovery);
        activeRun.recovering = true;
        McAgent.LOGGER.warn("[Recovery] Survival interrupt ({}); recovering before resuming",
                threat.reason());
        display(activeRun).note("Survival: " + threat.reason());
        launchNextAction();
    }

    private void handleFinishedAction(ServerPlayer player, AgentAction finished) {
        ActiveRun activeRun = run;
        if (activeRun == null) {
            return;
        }
        refreshSnapshot(player);
        // A build counts only when its own verification pass succeeded.
        if (finished instanceof com.bhautik.mcagent.action.BuildAction
                && finished.status() == ActionStatus.SUCCESS) {
            activeRun.buildVerified = true;
        }
        int current = activeRun.item == null ? 0
                : activeRun.snapshot.count(activeRun.item);
        // Reality first (PRD 14): if the goal is already satisfied, no
        // failure below matters.
        if (finished.status() != ActionStatus.CANCELLED && isSatisfied(activeRun, current)) {
            if (activeRun.enchantItemId != null) {
                // The table the agent just used becomes permanent base
                // infrastructure, so later enchants walk back to it.
                rememberEnchantTable(player);
            }
            if (finished instanceof com.bhautik.mcagent.action.FarmCropAction) {
                // Same for the crop plot: harvests return to one field.
                rememberFarmPlot(player);
            }
            if (activeRun.blueprint != null && activeRun.buildSite != null
                    && activeRun.buildVerified) {
                // Claim the ground only for a structure that really went
                // up; reserving failed sites fills the map with ghosts.
                reserveBuildSite(activeRun);
            }
            activeRun.goal.markSuccess();
            display(activeRun).finish("Done: " + activeRun.goal.title(), true);
            McAgent.LOGGER.info("[Agent] Goal completed: {}", activeRun.goal.title());
            run = null;
            return;
        }
        // Auto-equip crafted armor so worn pieces count immediately.
        if (finished instanceof com.bhautik.mcagent.action.CraftAction craft
                && craft.status() == ActionStatus.SUCCESS) {
            String resultId = craft.resultItemId();
            if (resultId.endsWith("_helmet") || resultId.endsWith("_chestplate")
                    || resultId.endsWith("_leggings") || resultId.endsWith("_boots")) {
                com.bhautik.mcagent.integration.VanillaEquipment.tryEquipArmor(player, resultId);
            }
        }
        if (finished instanceof RecoverAction
                || finished instanceof com.bhautik.mcagent.action.DepositAction
                || finished instanceof com.bhautik.mcagent.action.SurfaceAction) {
            activeRun.recovering = false;
            if (finished.status() == ActionStatus.SUCCESS) {
                activeRun.needsEmergencyFood = false;
                activeRun.securingFood = false;
            }
            // A stash that freed nothing must not be tried again: the
            // trigger condition is unchanged, so it re-fires immediately
            // and the goal livelocks instead of progressing.
            if (activeRun.stashing
                    && finished instanceof com.bhautik.mcagent.action.DepositAction deposit
                    && deposit.storedStacks() == 0) {
                activeRun.stashingIsFutile = true;
                McAgent.LOGGER.warn("[Recovery] Nothing in the bag is on the junk list;"
                        + " carrying on with a full inventory");
                display(activeRun).note("Nothing to stash; continuing with a full bag");
            }
            activeRun.stashing = false;
            McAgent.LOGGER.info("[Recovery] Survival step finished ({}); resuming plan",
                    finished.status().toString().toLowerCase());
        }
        switch (finished.status()) {
            case CANCELLED -> {
                run = null;
            }
            case FAILED -> {
                McAgent.LOGGER.warn("[Recovery] Action failed: {} ({})",
                        finished.title(), finished.failureReason());
                if (finished instanceof RecoverAction
                        && String.valueOf(finished.failureReason())
                                .contains(com.bhautik.mcagent.action.RecoverAction.NO_FOOD_MARKER)) {
                    // Starving with empty pockets: the next plan gathers
                    // emergency food before trying to recover again.
                    activeRun.needsEmergencyFood = true;
                    McAgent.LOGGER.info("[Recovery] No food in inventory; planning foraged meals");
                }
                if (finished.bestEffort()) {
                    // Cleanup steps never cost an attempt or sink the goal.
                    McAgent.LOGGER.info("[Recovery] Best-effort step failed; continuing: {}",
                            finished.title());
                    advanceOrFinish(activeRun, current);
                } else if (!finished.retryable()) {
                    // Settled fact about the world; a fresh plan would fail
                    // identically and repeat this step's side effects.
                    McAgent.LOGGER.info("[Recovery] Not retryable; failing now: {}",
                            finished.failureReason());
                    activeRun.goal.markFailed(finished.failureReason());
                    run = null;
                } else if (activeRun.attempts < MAX_PLAN_ATTEMPTS && replan(activeRun)) {
                    McAgent.LOGGER.info("[Recovery] Retrying with a fresh plan");
                } else {
                    finishWithFailure(activeRun);
                }
            }
            default -> advanceOrFinish(activeRun, current);
        }
    }

    /** Moves to the next queued step, replans, or closes the run out. */
    private void advanceOrFinish(ActiveRun activeRun, int current) {
        if (!runQueueIsEmpty(activeRun) && launchNextAction()) {
            McAgent.LOGGER.info("[Planner] Advancing plan: {}", executor.currentTitle());
        } else if (activeRun.attempts < MAX_PLAN_ATTEMPTS && replan(activeRun)) {
            McAgent.LOGGER.info("[Recovery] Retrying with a fresh plan");
        } else {
            String reason;
            if (activeRun.exploreTargetBiome != null) {
                reason = "target biome not reached after all attempts";
            } else if (activeRun.enchantItemId != null) {
                reason = shortName(activeRun.enchantItemId)
                        + " was not enchanted after all attempts";
            } else {
                reason = "verified inventory has " + current + "/"
                        + activeRun.requested + " after all attempts";
            }
            activeRun.goal.markFailed(reason);
            run = null;
        }
    }


    private static String rawName(net.minecraft.core.BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** Goal completion against live state, for items and exploration. */
    private boolean isSatisfied(ActiveRun activeRun, int current) {
        if (activeRun.kitItems != null) {
            return activeRun.kitItems.stream().allMatch(piece ->
                    liveCountById(activeRun.server, activeRun.playerId, piece.id())
                            >= activeRun.requested);
        }
        if (activeRun.blueprint != null) {
            // Only a build that actually verified counts. An empty queue
            // says the action STOPPED, not that it worked - a failed build
            // empties the queue too, and reporting that as success also
            // reserved ground for a structure that was never raised.
            return activeRun.buildVerified;
        }
        if (activeRun.enchantItemId != null) {
            // Carrying the item is not the goal - carrying an ENCHANTED one
            // is. The generic count check below is true from the start,
            // which declared success before anything was enchanted.
            return enchantedCountById(activeRun.server, activeRun.playerId,
                    activeRun.enchantItemId) > 0;
        }
        if (activeRun.restockIds != null || activeRun.stashIds != null) {
            return runQueueIsEmpty(activeRun);
        }
        if (activeRun.exploreTargetBiome != null) {
            return activeRun.exploreTargetBiome.equals(activeRun.biomeAt.current());
        }
        if (activeRun.structureTargetPos != null) {
            return activeRun.structureDistance.getAsDouble()
                    <= STRUCTURE_ARRIVE_DISTANCE_SQ;
        }
        return current >= activeRun.requested;
    }

    private boolean runQueueIsEmpty(ActiveRun activeRun) {
        return activeRun.queue.isEmpty();
    }

    /** Plans from scratch and fills the run's action queue. */
    /**
     * Drops an action left running by a dead goal.
     *
     * <p>The executor holds one action at a time and refuses to launch
     * while busy. An action orphaned by a run that ended - or stopped
     * behind our back, e.g. by Baritone's own cancel - therefore blocks
     * every future goal, and the failure surfaces as the misleading
     * "could not start any planned action".
     */
    private void clearStaleExecutor() {
        if (run == null && executor.busy()) {
            McAgent.LOGGER.info("[Recovery] Clearing an action left over from a"
                    + " finished goal before starting a new one");
            executor.cancelCurrent("previous goal ended");
        }
    }

    private boolean replan(ActiveRun activeRun) {
        // Reset pipeline flags first; planFor re-secures food if needed.
        activeRun.recovering = false;
        activeRun.securingFood = false;
        List<AgentAction> actions = planFor(activeRun);
        if (actions.isEmpty()) {
            return false;
        }
        activeRun.attempts++;
        activeRun.queue.clear();
        activeRun.queue.addAll(actions);
        activeRun.planSize = actions.size();
        display(activeRun).note("Planned " + actions.size() + " steps for "
                + activeRun.goal.title());
        return launchNextAction();
    }

    private boolean launchNextAction() {
        ActiveRun activeRun = run;
        if (activeRun == null || activeRun.queue.isEmpty()) {
            return false;
        }
        AgentAction next = activeRun.queue.poll();
        boolean launched = executor.launch(next);
        if (launched) {
            // Surface every step on screen as it starts; the boss bar is
            // the only place a live agent's progress is actually legible.
            int remaining = activeRun.queue.size();
            int total = Math.max(activeRun.planSize, remaining + 1);
            display(activeRun).showGoal(activeRun.goal.title(), next.title(),
                    total - remaining - 1, total);
        }
        return launched;
    }

    /**
     * Status display for the run's player, created once and reused.
     * Building a fresh one per update re-registered the boss bar every
     * tick, which is both wasteful and makes it flicker.
     */
    private com.bhautik.mcagent.action.AgentStatusDisplay display(ActiveRun activeRun) {
        if (activeRun.statusDisplay != null) {
            return activeRun.statusDisplay;
        }
        if (activeRun.server == null) {
            return com.bhautik.mcagent.action.AgentStatusDisplay.NONE;
        }
        ServerPlayer player = activeRun.server.getPlayerList()
                .getPlayer(activeRun.playerId);
        if (player == null) {
            return com.bhautik.mcagent.action.AgentStatusDisplay.NONE;
        }
        activeRun.statusDisplay =
                com.bhautik.mcagent.integration.VanillaStatusDisplay.forPlayer(player);
        return activeRun.statusDisplay;
    }

    private List<AgentAction> planFor(ActiveRun activeRun) {
        ServerPlayer player = activeRun.server.getPlayerList().getPlayer(activeRun.playerId);
        if (player == null) {
            return List.of();
        }
        if (activeRun.kitItems != null) {
            var collectedCounts = InventoryState.collect(player).itemCounts();
            var countsNow = new java.util.HashMap<>(collectedCounts);
            var baseSupplies = baseSuppliesFor(player);
            for (var entry : baseSupplies.entrySet()) {
                countsNow.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
            List<Map.Entry<String, Integer>> roots = activeRun.kitItems.stream()
                    .map(piece -> Map.entry(piece.id(), activeRun.requested))
                    .toList();
            var environment = environmentFor(activeRun, player, countsNow);
            var demanded = new java.util.LinkedHashSet<String>();
            List<AgentAction> actions = executor.planner().planAcquisition(
                    environment.resolver(),
                    id -> countsNow.getOrDefault(id, 0),
                    countsNow.keySet(),
                    itemId -> liveCountById(activeRun.server, activeRun.playerId, itemId),
                    environment,
                    roots,
                    demanded);
            var usefulSupplies = relevantSupplies(baseSupplies, demanded);
            if (!usefulSupplies.isEmpty()) {
                actions.addAll(0, supplyCollectionSteps(player, usefulSupplies));
                McAgent.LOGGER.info("[Planner] Base supplies credited: {}", usefulSupplies);
            }
            McAgent.LOGGER.info("[Planner] Kit plan generated: {}",
                    actions.stream().map(AgentAction::title).toList());
            return actions;
        }
        if (activeRun.blueprint != null) {
            var collected = InventoryState.collect(player).itemCounts();
            var countsNow = new java.util.HashMap<>(collected);
            // Materials sitting in the base chest count as owned, so the
            // build withdraws them instead of mining fresh ones.
            var baseSupplies = baseSuppliesFor(player);
            for (var entry : baseSupplies.entrySet()) {
                countsNow.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
            var environment = environmentFor(activeRun, player, countsNow);
            var demanded = new java.util.LinkedHashSet<String>();
            List<AgentAction> actions = executor.planner().planBuild(
                    environment.resolver(),
                    id -> countsNow.getOrDefault(id, 0),
                    countsNow.keySet(),
                    itemId -> liveCountById(activeRun.server, activeRun.playerId, itemId),
                    environment, activeRun.blueprint, activeRun.buildSite, demanded);
            var usefulSupplies = relevantSupplies(baseSupplies, demanded);
            if (!usefulSupplies.isEmpty()) {
                actions.addAll(0, supplyCollectionSteps(player, usefulSupplies));
                McAgent.LOGGER.info("[Planner] Base supplies credited: {}", usefulSupplies);
            }
            McAgent.LOGGER.info("[Planner] Build plan generated: {}",
                    actions.stream().map(AgentAction::title).toList());
            return actions;
        }
        if (activeRun.enchantItemId != null) {
            var collected = InventoryState.collect(player).itemCounts();
            var countsNow = new java.util.HashMap<>(collected);
            // Stored materials count as owned here too. Without this the
            // agent hunted cows and mined obsidian it already had sitting
            // in the base chest.
            var baseSupplies = baseSuppliesFor(player);
            for (var entry : baseSupplies.entrySet()) {
                countsNow.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
            var environment = environmentFor(activeRun, player, countsNow);
            String target = activeRun.enchantItemId;
            var demanded = new java.util.LinkedHashSet<String>();
            List<AgentAction> actions = executor.planner().planEnchant(
                    environment.resolver(),
                    id -> countsNow.getOrDefault(id, 0),
                    countsNow.keySet(),
                    itemId -> liveCountById(activeRun.server, activeRun.playerId, itemId),
                    environment,
                    com.bhautik.mcagent.integration.VanillaEnchanter.enchanter(player,
                            environment.enchantTableLocator()),
                    () -> enchantedCountById(activeRun.server, activeRun.playerId, target),
                    target,
                    activeRun.enchantMinLevel,
                    enchantingSiteFor(activeRun.server),
                    demanded);
            var usefulSupplies = relevantSupplies(baseSupplies, demanded);
            if (!usefulSupplies.isEmpty()) {
                actions.addAll(0, supplyCollectionSteps(player, usefulSupplies));
                McAgent.LOGGER.info("[Planner] Base supplies credited: {}", usefulSupplies);
            }
            McAgent.LOGGER.info("[Planner] Enchant plan generated: {}",
                    actions.stream().map(AgentAction::title).toList());
            return actions;
        }
        if (activeRun.restockIds != null) {
            var chest = baseChestPos(activeRun.server);
            if (chest == null) {
                activeRun.goal.markFailed("no base chest");
                return List.of();
            }
            var approach = com.bhautik.mcagent.integration.VanillaPlacementExecutor
                    .findApproachSpot(player, chest);
            var target = approach != null ? approach : chest;
            java.util.function.DoubleSupplier distance = () ->
                    player.distanceToSqr(target.getX(), target.getY(), target.getZ());
            List<AgentAction> actions = new ArrayList<>();
            if (player.blockPosition().distSqr(target) > 16.0) {
                actions.add(new com.bhautik.mcagent.action.MoveAction("base chest",
                        chest.getX(), chest.getY(), chest.getZ(),
                        4.0 * 4.0, distance::getAsDouble,
                        executor.baritoneIntegration()));
            }
            actions.add(new com.bhautik.mcagent.action.WithdrawAction(
                    "Restock from base", activeRun.restockIds,
                    com.bhautik.mcagent.integration.VanillaStorage.withdrawer(player,
                            com.bhautik.mcagent.integration.VanillaPlacementExecutor.INTERACTION_RADIUS)));
            return actions;
        }

        if (activeRun.stashIds != null) {
            var chest = baseChestPos(activeRun.server);
            if (chest == null) {
                activeRun.goal.markFailed("no base chest");
                return List.of();
            }
            var approach = com.bhautik.mcagent.integration.VanillaPlacementExecutor
                    .findApproachSpot(player, chest);
            var target = approach != null ? approach : chest;
            java.util.function.DoubleSupplier distance = () ->
                    player.distanceToSqr(target.getX(), target.getY(), target.getZ());
            List<AgentAction> actions = new ArrayList<>();
            if (player.blockPosition().distSqr(target) > 16.0) {
                actions.add(new com.bhautik.mcagent.action.MoveAction("base chest",
                        chest.getX(), chest.getY(), chest.getZ(),
                        4.0 * 4.0, distance::getAsDouble,
                        executor.baritoneIntegration()));
            }
            actions.add(new com.bhautik.mcagent.action.DepositAction(
                    "Store at base", activeRun.stashIds,
                    com.bhautik.mcagent.integration.VanillaStorage.depositor(player,
                            com.bhautik.mcagent.integration.VanillaPlacementExecutor.INTERACTION_RADIUS)));
            McAgent.LOGGER.info("[Planner] Plan generated: [{} Store at base]",
                    actions.size() > 1 ? "Travel," : "");
            return actions;
        }
        if (activeRun.exploreTargetBiome != null) {
            // M9: one open-ended explore step; verification is live biome.
            net.minecraft.core.BlockPos center = player.blockPosition();
            McAgent.LOGGER.info("[Planner] Plan generated: [Explore to {}]",
                    activeRun.exploreTargetBiome.replaceFirst("^minecraft:", ""));
            return List.of(new com.bhautik.mcagent.action.ExploreAction(
                    activeRun.exploreTargetBiome, center.getX(), center.getZ(),
                    activeRun.biomeAt::current, executor.baritoneIntegration()));
        }
        if (activeRun.structureTargetPos != null) {
            // M9 structures: walk to the located position; arrival is
            // verified against live distance by the goal check.
            var target = activeRun.structureTargetPos;
            McAgent.LOGGER.info("[Planner] Plan generated: [Travel to {}]", rawName(target));
            return List.of(new com.bhautik.mcagent.action.MoveAction(
                    activeRun.goal.title().replace("Explore to ", ""),
                    target.getX(), target.getY(), target.getZ(),
                    Math.sqrt(STRUCTURE_ARRIVE_DISTANCE_SQ),
                    () -> activeRun.structureDistance.getAsDouble(),
                    executor.baritoneIntegration()));
        }
        int current = activeRun.snapshot.count(activeRun.item);
        activeRun.snapshot.setCount(activeRun.item, current);
        try {
            var collectedCounts = InventoryState.collect(player).itemCounts();
            var countsNow = new java.util.HashMap<>(collectedCounts);
            var baseSupplies = baseSuppliesFor(player);
            // Items sitting in the base chest / furnace output count as
            // owned: the plan mines only what storage cannot cover.
            for (var entry : baseSupplies.entrySet()) {
                countsNow.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
            var environment = environmentFor(activeRun, player, countsNow);
            var demanded = new java.util.LinkedHashSet<String>();
            List<AgentAction> actions = executor.planner().planAcquisition(
                    new VanillaRecipeResolver(activeRun.server,
                            com.bhautik.mcagent.crafting.RecipeResolver.Grid.INVENTORY_2X2),
                    id -> countsNow.getOrDefault(id, 0),
                    countsNow.keySet(),
                    itemId -> liveCountById(activeRun.server, activeRun.playerId, itemId),
                    environment,
                    List.of(Map.entry(activeRun.item.id(), activeRun.requested)),
                    demanded);
            var usefulSupplies = relevantSupplies(baseSupplies, demanded);
            if (!usefulSupplies.isEmpty()) {
                actions.addAll(0, supplyCollectionSteps(player, usefulSupplies));
                McAgent.LOGGER.info("[Planner] Base supplies credited: {}", usefulSupplies);
            }
            if (activeRun.needsEmergencyFood) {
                List<AgentAction> forage = forageEmergencyFood(environment);
                if (!forage.isEmpty()) {
                    // Food first; the interruption system stands down
                    // (securingFood) until the recovery step consumes it.
                    actions.addAll(0, forage);
                    actions.add(new RecoverAction(activeRun.survivalMonitor,
                            VanillaSurvivalMonitor.feeder(player)));
                    activeRun.securingFood = true;
                }
                activeRun.needsEmergencyFood = false;
            }
            if (actions.isEmpty()) {
                activeRun.goal.markFailed(
                        "no supported acquisition strategy for " + activeRun.item.id());
            } else {
                McAgent.LOGGER.info("[Planner] Plan generated: {}",
                        actions.stream().map(AgentAction::title).toList());
            }
            return actions;
        } catch (com.bhautik.mcagent.planner.Planner.PlanningException planningFailure) {
            activeRun.goal.markFailed(planningFailure.getMessage());
            return List.of();
        }
    }

    /**
     * Items stored at the agent's feet - base chest contents plus any
     * finished smelts still inside nearby furnace output slots. Empty
     * when the agent is away from base.
     */
    /**
     * Narrows stored supplies to what the plan actually asked for.
     * Crediting every stored item is right — they count as owned, so the
     * plan mines less — but WITHDRAWING every stored item is not: a
     * leather goal was hauling 700 cobblestone out of the base chest,
     * filling the bag and triggering the auto-stash detour.
     */
    private static Map<String, Integer> relevantSupplies(
            Map<String, Integer> stored, Set<String> demanded) {
        Map<String, Integer> useful = new java.util.LinkedHashMap<>();
        for (var entry : stored.entrySet()) {
            if (demanded.contains(entry.getKey())) {
                useful.put(entry.getKey(), entry.getValue());
            }
        }
        return useful;
    }

    /**
     * Where the enchanting setup lives: the table already built at base
     * if there is one, otherwise the base anchor so a new table gets
     * built at home rather than wherever the agent is standing.
     * Null when no base has been set - the plan then places it in place.
     */
    private BlockLocator.BlockSite enchantingSiteFor(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        var state = com.bhautik.mcagent.integration.BaseSavedState.get(server);
        int[] table = state.enchantTable();
        if (table != null) {
            return new BlockLocator.BlockSite(table[0], table[1], table[2]);
        }
        int[] anchor = state.anchor();
        if (anchor[0] == 0 && anchor[1] == 0 && anchor[2] == 0) {
            return null; // no base set yet
        }
        return new BlockLocator.BlockSite(anchor[0], anchor[1], anchor[2]);
    }

    /**
     * Where crops get grown: the plot already tilled at base if there is
     * one, otherwise the base anchor so a new farm is started at home
     * rather than wherever the agent happened to be standing.
     */
    private BlockLocator.BlockSite farmSiteFor(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        var state = com.bhautik.mcagent.integration.BaseSavedState.get(server);
        int[] farm = state.farm();
        if (farm != null) {
            return new BlockLocator.BlockSite(farm[0], farm[1], farm[2]);
        }
        int[] anchor = state.anchor();
        if (anchor[0] == 0 && anchor[1] == 0 && anchor[2] == 0) {
            return null; // no base set yet
        }
        return new BlockLocator.BlockSite(anchor[0], anchor[1], anchor[2]);
    }

    /** Records the ground a finished structure occupies, with dimensions. */
    private void reserveBuildSite(ActiveRun activeRun) {
        if (activeRun.server == null) {
            return;
        }
        var site = activeRun.buildSite;
        var reservation = com.bhautik.mcagent.build.Reservation.centredOn(
                activeRun.blueprint.name(), activeRun.blueprint,
                site.x(), site.y(), site.z());
        com.bhautik.mcagent.integration.BaseSavedState.get(activeRun.server)
                .reserve(reservation);
        McAgent.LOGGER.info("[Build] Reserved {}", reservation.describe());
    }

    /** Remembers the crop plot so later harvests return to the same field. */
    private void rememberFarmPlot(ServerPlayer player) {
        if (player == null) {
            return;
        }
        var server = player.level().getServer();
        if (server == null) {
            return;
        }
        var state = com.bhautik.mcagent.integration.BaseSavedState.get(server);
        if (state.hasFarm()) {
            return;
        }
        var pos = player.blockPosition();
        state.setFarm(pos.getX(), pos.getY(), pos.getZ());
        McAgent.LOGGER.info("[Agent] Farm plot recorded at {} {} {}",
                pos.getX(), pos.getY(), pos.getZ());
    }

    /** Remembers a freshly built enchanting table as base infrastructure. */
    private void rememberEnchantTable(ServerPlayer player) {
        if (player == null) {
            return;
        }
        var server = player.level().getServer();
        if (server == null) {
            return;
        }
        var state = com.bhautik.mcagent.integration.BaseSavedState.get(server);
        if (state.hasEnchantTable()) {
            return;
        }
        com.bhautik.mcagent.integration.VanillaPlacementExecutor
                .blockLocator(player, com.bhautik.mcagent.planner.Planner.ENCHANTING_TABLE_ITEM,
                        com.bhautik.mcagent.integration.VanillaPlacementExecutor.INTERACTION_RADIUS)
                .nearestWithin(com.bhautik.mcagent.integration.VanillaPlacementExecutor
                        .INTERACTION_RADIUS)
                .ifPresent(site -> {
                    state.setEnchantTable(site.x(), site.y(), site.z());
                    McAgent.LOGGER.info("[Agent] Enchanting table recorded at {} {} {}",
                            site.x(), site.y(), site.z());
                });
    }

    /**
     * What the base chest holds, read from its RECORDED position rather
     * than from wherever the agent is standing.
     *
     * <p>This used to scan 4 blocks around the player, so stored stock
     * was only ever credited while the agent happened to be standing on
     * the chest. Every plan started away from base re-gathered materials
     * it already owned - hunting cows for leather that was in the box.
     */
    private Map<String, Integer> baseSuppliesFor(ServerPlayer player) {
        var server = player.level().getServer();
        var chest = baseChestPos(server);
        if (chest == null) {
            return Map.of();
        }
        return com.bhautik.mcagent.integration.VanillaStorage.storedTotalsAround(
                player.level(), chest,
                com.bhautik.mcagent.integration.VanillaPlacementExecutor.INTERACTION_RADIUS);
    }

    private AgentAction supplyWithdrawStep(ServerPlayer player,
            Map<String, Integer> supplies) {
        // Never pull tunnel junk back out of the chest: withdrawing the
        // credited cobblestone/granite refills the bag and defeats the
        // point of stashing. Only goal-relevant materials come out.
        List<String> withdrawable = supplies.keySet().stream()
                .filter(id -> !JUNK_ITEMS.contains(id))
                .toList();
        if (withdrawable.isEmpty()) {
            // Everything credited was junk (e.g. cobblestone goals):
            // nothing worth withdrawing; planning credit stands as-is.
            return new com.bhautik.mcagent.action.DepositAction(
                    "No supplies to collect", List.of(), (ids, maxStacks) -> 0);
        }
        Map<String, Integer> capped = new java.util.HashMap<>();
        withdrawable.forEach(id -> capped.put(id, supplies.get(id)));
        return new com.bhautik.mcagent.action.WithdrawAction(
                "Collect from base storage",
                List.copyOf(capped.keySet()),
                com.bhautik.mcagent.integration.VanillaStorage.supplyWithdrawer(
                        player, com.bhautik.mcagent.integration.VanillaPlacementExecutor.INTERACTION_RADIUS,
                        capped));
    }

    /**
     * Walk-to-chest plus the collect step. Credit is read from the base
     * chest wherever the agent is, so the plan MUST travel there before
     * withdrawing - otherwise it collects nothing and carries on
     * believing it owns materials that are still in the box.
     */
    private List<AgentAction> supplyCollectionSteps(ServerPlayer player,
            Map<String, Integer> supplies) {
        List<AgentAction> steps = new ArrayList<>();
        var chest = baseChestPos(player.level().getServer());
        if (chest != null) {
            var approach = com.bhautik.mcagent.integration.VanillaPlacementExecutor
                    .findApproachSpot(player, chest);
            var target = approach != null ? approach : chest;
            if (player.blockPosition().distSqr(target)
                    > com.bhautik.mcagent.integration.VanillaPlacementExecutor
                            .INTERACTION_RADIUS
                    * com.bhautik.mcagent.integration.VanillaPlacementExecutor
                            .INTERACTION_RADIUS) {
                steps.add(new com.bhautik.mcagent.action.MoveAction("base chest",
                        target.getX(), target.getY(), target.getZ(),
                        4.0 * 4.0,
                        () -> player.distanceToSqr(target.getX(), target.getY(),
                                target.getZ()),
                        executor.baritoneIntegration()));
            }
        }
        steps.add(supplyWithdrawStep(player, supplies));
        return steps;
    }

    /** Builds the full execution seam bundle against live world state. */
    private com.bhautik.mcagent.planner.Planner.Environment environmentFor(
            ActiveRun activeRun, ServerPlayer player,
            Map<String, Integer> countsNow) {
        return new com.bhautik.mcagent.planner.Planner.Environment(
                VanillaCraftingExecutor.forPlayer(player, activeRun.server),
                com.bhautik.mcagent.crafting.VanillaSmelter.forPlayer(player,
                        com.bhautik.mcagent.integration.VanillaPlacementExecutor.INTERACTION_RADIUS),
                com.bhautik.mcagent.integration.VanillaPlacementExecutor.placer(player),
                com.bhautik.mcagent.integration.VanillaPlacementExecutor.breaker(player,
                        com.bhautik.mcagent.integration.VanillaPlacementExecutor.INTERACTION_RADIUS),
                new VanillaRecipeResolver(activeRun.server,
                        com.bhautik.mcagent.crafting.RecipeResolver.Grid.INVENTORY_2X2),
                new com.bhautik.mcagent.crafting.VanillaSmeltingResolver(activeRun.server),
                com.bhautik.mcagent.integration.VanillaPlacementExecutor.blockLocator(player,
                        com.bhautik.mcagent.planner.Planner.CRAFTING_TABLE_ITEM,
                        com.bhautik.mcagent.integration.VanillaPlacementExecutor.INTERACTION_RADIUS),
                com.bhautik.mcagent.integration.VanillaPlacementExecutor.blockLocator(player,
                        com.bhautik.mcagent.planner.Planner.FURNACE_ITEM,
                        com.bhautik.mcagent.integration.VanillaPlacementExecutor.INTERACTION_RADIUS),
                com.bhautik.mcagent.integration.VanillaPlacementExecutor.blockLocator(player,
                        com.bhautik.mcagent.planner.Planner.ENCHANTING_TABLE_ITEM,
                        com.bhautik.mcagent.integration.VanillaPlacementExecutor.INTERACTION_RADIUS),
                (x, y, z) -> player.distanceToSqr(x, y, z),
                () -> player.level().getBiome(player.blockPosition()).unwrapKey()
                        .map(key -> key.identifier().toString()).orElse(""),
                new com.bhautik.mcagent.world.PositionAnchor() {
                    @Override public int x() { return player.blockPosition().getX(); }
                    // Tracked: obsidian-making needs depth to know whether
                    // it is still on the surface, and the default is
                    // Integer.MIN_VALUE, which reads as "already deep".
                    @Override public int y() { return player.blockPosition().getY(); }
                    @Override public int z() { return player.blockPosition().getZ(); }
                },
                com.bhautik.mcagent.integration.VanillaEquipment.equipper(player),
                com.bhautik.mcagent.integration.VanillaHunter.hunter(player,
                        com.bhautik.mcagent.integration.VanillaEquipment.equipper(player)),
                com.bhautik.mcagent.integration.VanillaXpSensor.sensor(player),
                com.bhautik.mcagent.integration.VanillaBreeder.breeder(player),
                com.bhautik.mcagent.integration.VanillaFluidHandler.handler(player),
                () -> com.bhautik.mcagent.integration.VanillaPlacementExecutor
                        .blockCount(player,
                                com.bhautik.mcagent.planner.Planner.OBSIDIAN_ITEM,
                                com.bhautik.mcagent.action.MakeObsidianAction.SEARCH_RADIUS),
                blockId -> com.bhautik.mcagent.integration.VanillaPlacementExecutor
                        .blockCount(player, blockId, VEIN_SCAN_RADIUS),
                com.bhautik.mcagent.integration.VanillaFarmer.farmer(player),
                farmSiteFor(activeRun.server),
                com.bhautik.mcagent.integration.VanillaStructureBuilder.builder(player));
    }

    /**
     * Plans gathering the first resolvable emergency food (berries,
     * mushrooms). Empty when none of the candidates can be resolved.
     */
    private List<AgentAction> forageEmergencyFood(
            com.bhautik.mcagent.planner.Planner.Environment environment) {
        ActiveRun activeRun = run;
        var countsNow = InventoryState.collect(
                activeRun.server.getPlayerList().getPlayer(activeRun.playerId)).itemCounts();
        for (String foodId : EMERGENCY_FOODS) {
            try {
                List<AgentAction> plan = executor.planner().planAcquisition(
                        new VanillaRecipeResolver(activeRun.server,
                                com.bhautik.mcagent.crafting.RecipeResolver.Grid.INVENTORY_2X2),
                        id -> countsNow.getOrDefault(id, 0),
                        countsNow.keySet(),
                        itemId -> liveCountById(activeRun.server, activeRun.playerId, itemId),
                        environment, foodId, EMERGENCY_FOOD_COUNT);
                if (!plan.isEmpty()) {
                    McAgent.LOGGER.info("[Planner] Emergency food plan: {}",
                            plan.stream().map(AgentAction::title).toList());
                    return plan;
                }
            } catch (com.bhautik.mcagent.planner.Planner.PlanningException ignored) {
                // try the next candidate food
            }
        }
        return List.of();
    }

    private String finishWithFailure(ActiveRun activeRun) {
        display(activeRun).finish("Failed: " + activeRun.goal.title()
                + (activeRun.goal.failureReason() == null ? ""
                        : " - " + activeRun.goal.failureReason()), false);
        activeRun.goal.markFailed(executor.baritoneIntegration().available()
                ? "could not start any planned action"
                : "no navigation backend available");
        McAgent.LOGGER.warn("[Agent] Goal failed: {} ({})",
                activeRun.goal.title(), activeRun.goal.failureReason());
        run = null;
        return activeRun.goal.progressReport();
    }

    private void abandonRun(String reason) {
        ActiveRun activeRun = run;
        if (activeRun == null) {
            return;
        }
        executor.cancelCurrent(reason);
        activeRun.goal.markFailed(reason);
        McAgent.LOGGER.warn("[Agent] Goal abandoned: {} ({})", activeRun.goal.title(), reason);
        run = null;
    }

    private void refreshSnapshot(ServerPlayer player) {
        ActiveRun activeRun = run;
        if (activeRun == null || activeRun.item == null) {
            return; // exploration runs track biomes, not item counts
        }
        activeRun.snapshot.setCount(activeRun.item, liveCount(player, activeRun));
    }

    private static int liveCount(ServerPlayer player, ActiveRun activeRun) {
        return liveCountById(player.level().getServer(), player.getUUID(), activeRun.item.id());
    }

    /** Enchanted copies of an item the agent carries (enchant verification). */
    private static int enchantedCountById(MinecraftServer server, UUID playerId,
                                          String itemId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return 0;
        }
        var inventory = player.getInventory();
        int found = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.getEnchantments().isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (id.equals(itemId)) {
                found += stack.getCount();
            }
        }
        return found;
    }

    private static int liveCountById(MinecraftServer server, UUID playerId, String itemId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return 0;
        }
        return InventoryState.collect(player).itemCounts().getOrDefault(itemId, 0);
    }

    private static int liveCount(MinecraftServer server, UUID playerId, MinecraftItem item) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return 0;
        }
        return InventoryState.collect(player).itemCounts().getOrDefault(item.id(), 0);
    }

    private static dev.minecraftai.agent.world.InventoryState snapshot(ServerPlayer player) {
        dev.minecraftai.agent.world.InventoryState snapshot = new dev.minecraftai.agent.world.InventoryState();
        InventoryState.collect(player).itemCounts()
                .forEach((itemId, count) -> snapshot.setCount(new MinecraftItem(itemId), count));
        return snapshot;
    }

    private static final class ActiveRun {
        final AgentGoal goal;
        final Deque<AgentAction> queue = new ArrayDeque<>();
        final MinecraftItem item;
        final UUID playerId;
        final int requested;
        final dev.minecraftai.agent.world.InventoryState snapshot;
        final MinecraftServer server;
        final com.bhautik.mcagent.survival.SurvivalMonitor survivalMonitor;
        int attempts;
        long tickCount;
        /** True while a survival recovery step is in the pipeline. */
        boolean recovering;
        /** Set when a recovery starved; next plan gathers food first. */
        boolean needsEmergencyFood;
        /** True while the queue works through gathered emergency food. */
        boolean securingFood;
        /** Non-null for kit runs (UC-09): every piece must be carried. */
        List<MinecraftItem> kitItems;
        /** Non-null for exploration runs (M9): the qualified biome id. */
        String exploreTargetBiome;
        /** Live biome under the agent, wired only for exploration runs. */
        com.bhautik.mcagent.world.BiomeSensor biomeAt;
        /** Non-null for structure runs (M9): the located block position. */
        net.minecraft.core.BlockPos structureTargetPos;
        /** Live distance to the structure target, wired with biomeAt. */
        java.util.function.DoubleSupplier structureDistance;

        /** Non-null for stash runs: the ids allowed into the chest. */
        List<String> stashIds;
        /** Non-null for restock runs: the ids pulled from the chest. */
        List<String> restockIds;
        int restockMaxStacks;
        /** Steps in the current plan, for on-screen progress. */
        int planSize;
        /** Created once so the boss bar stays put instead of flickering. */
        com.bhautik.mcagent.action.AgentStatusDisplay statusDisplay;
        /** True while an auto-stash detour is in the pipeline. */
        boolean stashing;
        /**
         * Set when a stash freed no slots, so the detour is not retried
         * on an unchanged inventory. Cleared once space appears.
         */
        boolean stashingIsFutile;
        /** Non-null while a hostile is being fought. */
        String combatTarget;
        /** Non-null for build runs: the structure to raise. */
        com.bhautik.mcagent.build.Blueprint blueprint;
        /** Where a build run puts the structure. */
        BlockLocator.BlockSite buildSite;
        /** Set only when the build action verified the finished structure. */
        boolean buildVerified;
        /** Non-null for enchant runs: the item id to enchant. */
        String enchantItemId;
        /** Lowest acceptable offer level for an enchant run. */
        int enchantMinLevel = 1;

        ActiveRun(AgentGoal goal, MinecraftItem item, UUID playerId, int requested,
                  dev.minecraftai.agent.world.InventoryState snapshot, MinecraftServer server,
                  com.bhautik.mcagent.survival.SurvivalMonitor survivalMonitor) {
            this.goal = goal;
            this.item = item;
            this.playerId = playerId;
            this.requested = requested;
            this.snapshot = snapshot;
            this.server = server;
            this.survivalMonitor = survivalMonitor;
        }
    }
}
