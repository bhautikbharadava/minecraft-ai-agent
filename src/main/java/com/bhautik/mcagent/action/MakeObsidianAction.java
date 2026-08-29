package com.bhautik.mcagent.action;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.integration.BaritoneIntegration;
import com.bhautik.mcagent.world.BlockLocator;

import java.util.function.IntSupplier;

/**
 * Makes obsidian the way players do: pour water onto a lava source.
 *
 * <p>Natural obsidian is scarce and only forms beside underground lava,
 * so mining for it is mostly walking. With a bucket the agent can
 * manufacture as much as it needs wherever lava exists.
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
    /** Ticks before the attempt is abandoned. */
    public static final int TIMEOUT_TICKS = 20 * 90;

    private final String title;
    private final int targetCount;
    /** Obsidian blocks standing within reach, not items carried. */
    private final IntSupplier obsidianBlocksNearby;
    private final FluidHandler fluids;
    private final BaritoneIntegration navigation;
    private final com.bhautik.mcagent.world.DistanceSensor distance;

    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private boolean noLava;
    private int baseline;
    private int ticks;
    private BlockLocator.BlockSite goal;

    public MakeObsidianAction(int targetCount, IntSupplier obsidianBlocksNearby,
                              FluidHandler fluids, BaritoneIntegration navigation,
                              com.bhautik.mcagent.world.DistanceSensor distance) {
        this.targetCount = targetCount;
        this.obsidianBlocksNearby = obsidianBlocksNearby;
        this.fluids = fluids;
        this.navigation = navigation;
        this.distance = distance;
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
        if (fluids.nearest(LAVA, SEARCH_RADIUS).isEmpty()) {
            noLava = true;
            fail("no lava source within " + SEARCH_RADIUS + " blocks");
            return;
        }
        status = ActionStatus.RUNNING;
        McAgent.LOGGER.info("[Action] Started: {}", title);
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
            fail("no obsidian formed within " + (TIMEOUT_TICKS / 20) + "s");
            return;
        }
        // Fill first, then pour: the bucket dictates which fluid we seek.
        String wanted = fluids.carriesWater() ? LAVA : WATER;
        var site = fluids.nearest(wanted, SEARCH_RADIUS).orElse(null);
        if (site == null) {
            if (LAVA.equals(wanted)) {
                noLava = true;
            }
            navigation.stop();
            fail("no " + wanted.replaceFirst("^minecraft:", "")
                    + " source within " + SEARCH_RADIUS + " blocks");
            return;
        }
        if (!withinReach(site)) {
            walkTo(site);
            return;
        }
        boolean acted = fluids.carriesWater()
                ? fluids.pourOnto(site)
                : fluids.fillFrom(site);
        if (acted) {
            // Next tick re-reads the world; obsidian is verified by count.
            goal = null;
        }
    }

    @Override
    public void cancel() {
        if (!status.terminal()) {
            navigation.stop();
            status = ActionStatus.CANCELLED;
        }
    }

    /** Without lava there is nothing to convert; replanning cannot help. */
    @Override
    public boolean retryable() {
        return !noLava;
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

    private void fail(String reason) {
        failureReason = reason;
        status = ActionStatus.FAILED;
        McAgent.LOGGER.warn("[Recovery] Action failed: {} ({})", title, reason);
    }
}
