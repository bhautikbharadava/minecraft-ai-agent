package com.bhautik.mcagent.action;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.build.Blueprint;
import com.bhautik.mcagent.integration.BaritoneIntegration;
import com.bhautik.mcagent.world.BlockLocator;

/**
 * Raises a {@link Blueprint} at a fixed origin, a few blocks per tick,
 * then checks the finished structure against the design.
 *
 * <p>Success is a verification pass over the world, not a count of
 * placements attempted: a block that failed to seat, or that something
 * else overwrote, has to show up as a failure rather than a build the
 * agent believes it completed.
 */
public final class BuildAction implements AgentAction {

    /**
     * Blocks placed per tick. One, deliberately: eight a tick finished a
     * 9x9 in half a second, which reads as blocks teleporting into place
     * and gives no chance to see it go wrong and cancel.
     */
    public static final int PLACEMENTS_PER_TICK = 1;
    /** Ticks allowed before the build is abandoned. */
    public static final int TIMEOUT_TICKS = 20 * 120;
    /**
     * Placements allowed to fail before the build is called off. Some
     * slack absorbs a block the agent could not reach on the first pass.
     */
    public static final int MAX_MISSES = 24;
    /** Arm's length, matching vanilla survival build reach. */
    public static final double REACH = 4.5;
    /** Ticks spent trying to reach one block before skipping it. */
    public static final int WALK_TIMEOUT_TICKS = 20 * 15;

    private final String title;
    private final Blueprint blueprint;
    private final BlockLocator.BlockSite origin;
    private final StructureBuilder builder;
    private final BaritoneIntegration navigation;
    private final com.bhautik.mcagent.world.DistanceSensor distance;

    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private int cursor;
    private int placed;
    private int misses;
    private int ticks;
    private boolean verifying;
    private boolean refused;
    private BlockLocator.BlockSite walkingTo;
    private int walkTicks;
    private String firstMiss;

    public BuildAction(Blueprint blueprint, BlockLocator.BlockSite origin,
                       StructureBuilder builder, BaritoneIntegration navigation,
                       com.bhautik.mcagent.world.DistanceSensor distance) {
        this.distance = distance;
        this.blueprint = blueprint;
        this.origin = origin;
        this.builder = builder;
        this.navigation = navigation;
        this.title = "Build " + blueprint.describe();
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
        if (blueprint.placements().isEmpty()) {
            fail("blueprint is empty");
            return;
        }
        // Survey the whole footprint BEFORE mutating anything. Building
        // over a chest deletes its contents with no drops, and no amount
        // of later verification can undo that.
        var blocked = surveyForProtected();
        if (blocked != null) {
            refused = true;
            fail("refusing to build: " + blocked
                    + " is in the way and would be destroyed."
                    + " Move the build site or clear it yourself.");
            return;
        }
        status = ActionStatus.RUNNING;
        McAgent.LOGGER.info("[Action] Started: {} at {} {} {}", title,
                origin.x(), origin.y(), origin.z());
    }

    @Override
    public void tick() {
        if (status != ActionStatus.RUNNING) {
            return;
        }
        if (++ticks > TIMEOUT_TICKS) {
            fail("build did not finish within " + (TIMEOUT_TICKS / 20) + "s");
            return;
        }
        if (verifying) {
            verify();
            return;
        }
        for (int step = 0; step < PLACEMENTS_PER_TICK; step++) {
            if (cursor >= blueprint.placements().size()) {
                verifying = true;
                McAgent.LOGGER.info("[Action] {} placed {} blocks; verifying",
                        title, placed);
                return;
            }
            var next = blueprint.placements().get(cursor);
            // Build the way a player does: walk to the block and place it
            // from arm's length, rather than materialising it across the
            // site from wherever the agent happens to stand.
            if (!withinReach(next)) {
                walkTo(next);
                return;
            }
            cursor++;
            applyOne(next);
            walkingTo = null;
        }
    }

    private boolean withinReach(Blueprint.Placement placement) {
        var at = siteOf(placement);
        return distance.distanceSquaredTo(at.x(), at.y(), at.z())
                <= REACH * REACH;
    }

    /** Walks toward the next block, re-issuing only when the target moves on. */
    private void walkTo(Blueprint.Placement placement) {
        var at = siteOf(placement);
        if (at.equals(walkingTo)) {
            if (++walkTicks > WALK_TIMEOUT_TICKS) {
                // Cannot get to this one; skip it and let verification
                // report the gap rather than stalling the whole build.
                McAgent.LOGGER.warn("[Action] Could not reach {} {} {}; skipping",
                        at.x(), at.y(), at.z());
                cursor++;
                misses++;
                walkingTo = null;
                walkTicks = 0;
            }
            return;
        }
        walkingTo = at;
        walkTicks = 0;
        navigation.startGoTo(at.x(), at.y(), at.z());
    }

    /**
     * First protected block inside the footprint, described for the
     * failure message, or null when the site is safe to build on.
     */
    private String surveyForProtected() {
        for (Blueprint.Placement placement : blueprint.placements()) {
            var at = siteOf(placement);
            if (!builder.isProtected(at)) {
                continue;
            }
            return builder.blockAt(at).replaceFirst("^minecraft:", "")
                    + " at " + at.x() + " " + at.y() + " " + at.z();
        }
        return null;
    }

    private BlockLocator.BlockSite siteOf(Blueprint.Placement placement) {
        return new BlockLocator.BlockSite(
                origin.x() + placement.dx(),
                origin.y() + placement.dy(),
                origin.z() + placement.dz());
    }

    private void applyOne(Blueprint.Placement placement) {
        var at = siteOf(placement);
        boolean done = Blueprint.AIR.equals(placement.blockId())
                ? builder.clear(at)
                : builder.place(at, placement.blockId());
        if (done) {
            placed++;
            return;
        }
        // Already correct counts as done; only a genuine mismatch misses.
        if (placement.blockId().equals(builder.blockAt(at))) {
            return;
        }
        misses++;
        if (firstMiss == null) {
            firstMiss = placement.blockId().replaceFirst("^minecraft:", "")
                    + " at " + at.x() + " " + at.y() + " " + at.z()
                    + " (ground is " + builder.blockAt(at).replaceFirst("^minecraft:", "")
                    + ")";
        }
        if (misses > MAX_MISSES) {
            fail("could not place " + misses + " blocks; first was " + firstMiss);
        }
    }

    /** Compares the finished structure with the blueprint, block by block. */
    private void verify() {
        int wrong = 0;
        String firstWrong = null;
        for (Blueprint.Placement placement : blueprint.placements()) {
            if (Blueprint.AIR.equals(placement.blockId())) {
                continue; // cleared space is not worth failing over
            }
            var at = siteOf(placement);
            if (!placement.blockId().equals(builder.blockAt(at))) {
                wrong++;
                if (firstWrong == null) {
                    firstWrong = placement.blockId().replaceFirst("^minecraft:", "")
                            + " at " + at.x() + " " + at.y() + " " + at.z();
                }
            }
        }
        navigation.stop();
        if (wrong == 0) {
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Verified success: {} ({} blocks)",
                    title, placed);
            return;
        }
        fail(wrong + " blocks do not match the blueprint (first: " + firstWrong + ")");
    }

    @Override
    public void cancel() {
        if (!status.terminal()) {
            navigation.stop();
            status = ActionStatus.CANCELLED;
        }
    }

    /** A blocked site stays blocked; replanning would refuse identically. */
    @Override
    public boolean retryable() {
        return !refused;
    }

    private void fail(String reason) {
        failureReason = reason;
        status = ActionStatus.FAILED;
        McAgent.LOGGER.warn("[Recovery] Action failed: {} ({})", title, reason);
    }
}
