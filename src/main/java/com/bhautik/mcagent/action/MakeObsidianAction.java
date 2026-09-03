package com.bhautik.mcagent.action;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.integration.BaritoneIntegration;
import com.bhautik.mcagent.world.BlockLocator;
import com.bhautik.mcagent.world.PositionAnchor;

import java.util.function.IntSupplier;

/**
 * Makes obsidian the way players do: pour water onto a lava source.
 *
 * <p>Natural obsidian is scarce and only forms beside underground lava,
 * so mining for it is mostly walking. With a bucket the agent can
 * manufacture as much as it needs wherever lava exists.
 *
 * <p>Finding the lava is most of the work. Lava sits underground, so
 * standing on the surface and scanning a fixed radius finds nothing;
 * this descends to the depth where lava lakes generate and searches
 * from there, the same way hunting roams to find a herd.
 *
 * <p>Pouring creates obsidian BLOCKS, not items, so success is counted
 * in blocks that appeared nearby; the plan's existing mine step then
 * harvests them. Never trust the pour call itself — water only converts
 * a true SOURCE block.
 */
public final class MakeObsidianAction implements AgentAction {

    public static final String WATER = "minecraft:water";
    public static final String LAVA = "minecraft:lava";
    /** How far to look for the fluids involved. */
    public static final int SEARCH_RADIUS = 32;
    /** Close enough to use a bucket on a block. */
    public static final double USE_DISTANCE_SQ = 16.0;
    /**
     * Depth lava lakes generate at. Above this, searching the surface is
     * wasted effort - the agent digs down to look.
     */
    public static final int LAVA_DEPTH = 11;
    /** Below this the agent is deep enough to start searching. */
    public static final int DEEP_ENOUGH_Y = 24;
    /** Ticks between world scans; scanning every tick is needless load. */
    public static final int SCAN_INTERVAL_TICKS = 10;
    /** Ticks spent descending before giving up on reaching depth. */
    public static final int DESCEND_TIMEOUT_TICKS = 20 * 120;
    /** Ticks spent roaming at depth before admitting there is no lava. */
    public static final int SEEK_TIMEOUT_TICKS = 20 * 120;
    /** Phase switches allowed before the action admits it is thrashing. */
    public static final int MAX_PHASE_CHANGES = 40;
    /** Tries against one lava source before writing it off. */
    public static final int POUR_ATTEMPTS_PER_SOURCE = 40;
    /** Ticks spent trying to scoop the poured water back up. */
    public static final int RECLAIM_TIMEOUT_TICKS = 20 * 15;
    /** Ticks before the whole attempt is abandoned. */
    public static final int TIMEOUT_TICKS = 20 * 300;

    private enum Phase { FILLING, DESCENDING, SEEKING, POURING, RECLAIMING }

    private final String title;
    private final int targetCount;
    /** Obsidian blocks standing within reach, not items carried. */
    private final IntSupplier obsidianBlocksNearby;
    private final FluidHandler fluids;
    private final BaritoneIntegration navigation;
    private final com.bhautik.mcagent.world.DistanceSensor distance;
    private final PositionAnchor anchor;

    private Phase phase = Phase.FILLING;
    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private boolean searchExhausted;
    private int baseline;
    private int ticks;
    private int phaseTicks;
    private int sinceScan = Integer.MAX_VALUE;
    private BlockLocator.BlockSite cachedTarget;
    private String cachedFluid;
    private BlockLocator.BlockSite goal;
    /** Where the last pour left water, so the bucket can take it back. */
    private BlockLocator.BlockSite waterAt;
    /** Lava that cannot be poured onto - buried, or with no room above. */
    private final java.util.Set<BlockLocator.BlockSite> unusableLava =
            new java.util.HashSet<>();
    private int pourAttempts;
    private int phaseChanges;

    public MakeObsidianAction(int targetCount, IntSupplier obsidianBlocksNearby,
                              FluidHandler fluids, BaritoneIntegration navigation,
                              com.bhautik.mcagent.world.DistanceSensor distance,
                              PositionAnchor anchor) {
        this.targetCount = targetCount;
        this.obsidianBlocksNearby = obsidianBlocksNearby;
        this.fluids = fluids;
        this.navigation = navigation;
        this.distance = distance;
        this.anchor = anchor;
        this.title = "Make " + targetCount + " obsidian (water on lava)";
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public ActionStatus status() {
        return status;
    }

    @Override
    public String failureReason() {
        return failureReason;
    }

    @Override
    public void start() {
        if (status != ActionStatus.PENDING) {
            return;
        }
        baseline = Math.max(obsidianBlocksNearby.getAsInt(), 0);
        // A filled bucket means the water leg is already done.
        phase = fluids.carriesWater() ? Phase.SEEKING : Phase.FILLING;
        status = ActionStatus.RUNNING;
        McAgent.LOGGER.info("[Action] Started: {} (phase {})", title, phase);
    }

    @Override
    public void tick() {
        if (status != ActionStatus.RUNNING) {
            return;
        }
        int count = Math.max(obsidianBlocksNearby.getAsInt(), 0);
        if (count >= baseline + targetCount) {
            navigation.stop();
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Verified success: {} ({} blocks)", title, count);
            return;
        }
        if (++ticks > TIMEOUT_TICKS) {
            navigation.stop();
            fail("no obsidian formed within " + (TIMEOUT_TICKS / 20 / 60) + " minutes");
            return;
        }
        phaseTicks++;
        sinceScan++;
        switch (phase) {
            case FILLING -> fillBucket();
            case DESCENDING -> descend();
            case SEEKING -> seekLava();
            case POURING -> pour();
            case RECLAIMING -> reclaimWater();
        }
    }

    private void fillBucket() {
        if (fluids.carriesWater()) {
            enter(Phase.SEEKING);
            return;
        }
        var water = scanFor(WATER);
        if (water == null) {
            fail("no water source within " + SEARCH_RADIUS + " blocks to fill a bucket");
            return;
        }
        if (!withinReach(water)) {
            walkTo(water);
            return;
        }
        if (fluids.fillFrom(water)) {
            enter(Phase.SEEKING);
        }
    }

    /**
     * Digs toward the depth lava generates at. Baritone tunnels to reach
     * the goal, so this doubles as the "get underground" step.
     */
    private void descend() {
        var lava = pourableLava();
        if (lava != null) {
            enter(Phase.POURING);
            return;
        }
        if (anchor.y() <= DEEP_ENOUGH_Y) {
            enter(Phase.SEEKING);
            return;
        }
        if (goal == null) {
            goal = new BlockLocator.BlockSite(anchor.x(), LAVA_DEPTH, anchor.z());
            navigation.startGoTo(goal.x(), goal.y(), goal.z());
            McAgent.LOGGER.info("[Action] No lava up here; digging down to y{}",
                    LAVA_DEPTH);
        }
        if (phaseTicks > DESCEND_TIMEOUT_TICKS) {
            navigation.stop();
            searchExhausted = true;
            fail("could not dig down to lava depth");
        }
    }

    /** Roams at depth until lava turns up, or the search budget runs out. */
    private void seekLava() {
        var lava = pourableLava();
        if (lava != null) {
            navigation.stop();
            goal = null;
            enter(Phase.POURING);
            return;
        }
        if (anchor.y() > DEEP_ENOUGH_Y) {
            // Still on the surface: get under first, lava is not up here.
            goal = null;
            enter(Phase.DESCENDING);
            return;
        }
        if (goal == null) {
            goal = new BlockLocator.BlockSite(anchor.x(), anchor.y(), anchor.z());
            navigation.startExplore(anchor.x(), anchor.z());
            McAgent.LOGGER.info("[Action] Roaming underground to find lava");
        }
        if (phaseTicks > SEEK_TIMEOUT_TICKS) {
            navigation.stop();
            searchExhausted = true;
            fail("no lava found after searching underground for "
                    + (SEEK_TIMEOUT_TICKS / 20) + "s");
        }
    }

    private void pour() {
        var lava = pourableLava();
        if (lava == null) {
            // Nothing here we can use; roam for a different lake rather
            // than pouring at the same buried source forever.
            goal = null;
            enter(Phase.SEEKING);
            return;
        }
        if (!fluids.carriesWater()) {
            enter(Phase.FILLING);
            return;
        }
        if (!withinReach(lava)) {
            walkTo(lava);
            return;
        }
        if (fluids.pourOnto(lava)) {
            // Water is poured into the block above the lava; remember it so
            // the bucket can be refilled from it.
            waterAt = new BlockLocator.BlockSite(lava.x(), lava.y() + 1, lava.z());
            pourAttempts = 0;
            invalidateScan();
            goal = null;
            enter(Phase.RECLAIMING);
            return;
        }
        // Pouring can fail forever on one source - lava buried under rock
        // has nowhere to take the water. Give up on this one and look
        // elsewhere instead of retrying it every tick.
        if (++pourAttempts > POUR_ATTEMPTS_PER_SOURCE) {
            unusableLava.add(lava);
            pourAttempts = 0;
            invalidateScan();
            goal = null;
            McAgent.LOGGER.info("[Action] Cannot pour onto lava at {} {} {}"
                    + " (no room above?); looking for another source",
                    lava.x(), lava.y(), lava.z());
            enter(Phase.SEEKING);
        }
    }

    /**
     * Takes the poured water back, the way a player does.
     *
     * <p>Two reasons this matters: the bucket is reusable, so the next
     * pour needs no trip back to a water source; and obsidian left under
     * water is awkward to mine - navigation avoids it - so the block we
     * just made would sit there unharvested.
     */
    private void reclaimWater() {
        if (waterAt == null) {
            enter(Phase.SEEKING);
            return;
        }
        if (fluids.carriesWater()) {
            // Already holding it; back to work.
            waterAt = null;
            enter(Phase.SEEKING);
            return;
        }
        if (!withinReach(waterAt)) {
            walkTo(waterAt);
            return;
        }
        if (fluids.fillFrom(waterAt)) {
            McAgent.LOGGER.info("[Action] Picked the water back up");
            waterAt = null;
            invalidateScan();
            goal = null;
            enter(Phase.SEEKING);
            return;
        }
        // The water flowed away or was never a source; carry on without it
        // rather than standing here.
        if (phaseTicks > RECLAIM_TIMEOUT_TICKS) {
            McAgent.LOGGER.warn("[Action] Could not recover the poured water");
            waterAt = null;
            enter(Phase.FILLING);
        }
    }

    /**
     * Throttled world scan; fluid searches are far too costly to run
     * every tick. Keyed by fluid, or a cached water position would be
     * handed back when lava was asked for.
     */
    private BlockLocator.BlockSite pourableLava() {
        if (sinceScan < SCAN_INTERVAL_TICKS && LAVA.equals(cachedFluid)) {
            return cachedTarget;
        }
        sinceScan = 0;
        cachedFluid = LAVA;
        cachedTarget = fluids.nearestPourable(SEARCH_RADIUS).orElse(null);
        return cachedTarget;
    }

    private BlockLocator.BlockSite scanFor(String fluidId) {
        if (fluidId.equals(cachedFluid) && sinceScan < SCAN_INTERVAL_TICKS) {
            return cachedTarget;
        }
        sinceScan = 0;
        cachedFluid = fluidId;
        cachedTarget = fluids.nearest(fluidId, SEARCH_RADIUS).orElse(null);
        return cachedTarget;
    }

    private void invalidateScan() {
        cachedTarget = null;
        cachedFluid = null;
        sinceScan = Integer.MAX_VALUE;
    }

    private boolean withinReach(BlockLocator.BlockSite site) {
        return distance.distanceSquaredTo(site.x(), site.y(), site.z()) <= USE_DISTANCE_SQ;
    }

    private void walkTo(BlockLocator.BlockSite site) {
        if (site.equals(goal)) {
            return;
        }
        goal = site;
        navigation.startGoTo(site.x(), site.y(), site.z());
    }

    private void enter(Phase next) {
        // Phase changes reset the per-phase clock, so two phases flipping
        // back and forth would never time out. Count the flips and fail
        // honestly rather than thrashing until the global timeout.
        if (++phaseChanges > MAX_PHASE_CHANGES) {
            navigation.stop();
            searchExhausted = true;
            fail("stopped making progress (cycled between " + phase + " and "
                    + next + "); no usable lava reachable here");
            return;
        }
        phase = next;
        phaseTicks = 0;
        invalidateScan();
        McAgent.LOGGER.info("[Action] {} -> {}", title, next);
    }

    @Override
    public void cancel() {
        if (!status.terminal()) {
            navigation.stop();
            status = ActionStatus.CANCELLED;
        }
    }

    /** A world with no reachable lava stays that way; replanning cannot help. */
    @Override
    public boolean retryable() {
        return !searchExhausted;
    }

    /**
     * Making obsidian is a shortcut, not the goal: the plan still carries
     * a mine step for it. Failing here must fall through to mining
     * whatever generated naturally rather than sinking the whole goal.
     */
    @Override
    public boolean bestEffort() {
        return true;
    }

    private void fail(String reason) {
        failureReason = reason;
        status = ActionStatus.FAILED;
        McAgent.LOGGER.warn("[Recovery] Action failed: {} ({})", title, reason);
    }
}
