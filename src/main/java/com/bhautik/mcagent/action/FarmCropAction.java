package com.bhautik.mcagent.action;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.integration.BaritoneIntegration;
import com.bhautik.mcagent.world.BlockLocator;

import java.util.function.IntSupplier;

/**
 * Grows a crop from seed and harvests it: till ground beside water, sow,
 * wait (or spend bone meal), then reap.
 *
 * <p>Phases are an explicit enum rather than a set of booleans. The
 * hunting action's flag soup produced a tick-loop bug that took a live
 * client to find, and this has more states than that one did.
 */
public final class FarmCropAction implements AgentAction {

    /** How far to look for tillable ground and for our own crops. */
    public static final int RADIUS = 12;
    /**
     * Ticks allowed for crops to ripen on the world clock. Wheat takes
     * minutes without bone meal, so this is deliberately generous.
     */
    public static final int GROW_TIMEOUT_TICKS = 20 * 60 * 8;
    /** Ticks for the shorter, mechanical phases. */
    public static final int PHASE_TIMEOUT_TICKS = 20 * 45;
    /**
     * Phase switches allowed before the action admits it is going in
     * circles. Every phase change resets the per-phase clock, so a cycle
     * of them would otherwise never time out - tilling and sowing can
     * both report success while no crop ever registers, and the action
     * loops TILLING -> SOWING -> GROWING -> TILLING forever.
     */
    public static final int MAX_PHASE_CHANGES = 24;
    /** Hard ceiling on the whole attempt, whatever the phases do. */
    public static final int TOTAL_TIMEOUT_TICKS = 20 * 60 * 12;
    /**
     * Half-width of the field tilled around the chosen spot. Four keeps
     * every block inside vanilla's hydration range of the water it was
     * sited next to, so the farmland does not dry out.
     */
    public static final int PLOT_RADIUS = 4;

    private enum Phase { TILLING, SOWING, GROWING, HARVESTING }

    private final String title;
    private final String cropBlockId;
    private final String seedItemId;
    private final int targetCount;
    private final IntSupplier liveCropCount;
    private final Farmer farmer;
    private final BaritoneIntegration navigation;

    private Phase phase = Phase.TILLING;
    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private boolean noGround;
    private int baseline;
    private int sown;
    private int phaseTicks;
    private int totalTicks;
    private int phaseChanges;
    private BlockLocator.BlockSite plot;

    public FarmCropAction(String cropBlockId, String seedItemId, int targetCount,
                          IntSupplier liveCropCount, Farmer farmer,
                          BaritoneIntegration navigation) {
        this.cropBlockId = cropBlockId;
        this.seedItemId = seedItemId;
        this.targetCount = targetCount;
        this.liveCropCount = liveCropCount;
        this.farmer = farmer;
        this.navigation = navigation;
        this.title = "Farm " + targetCount + " "
                + cropBlockId.replaceFirst("^minecraft:", "");
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
        baseline = Math.max(liveCropCount.getAsInt(), 0);
        // Skip planting only when the field ALREADY holds enough crops to
        // meet the order. Treating a couple of leftover stalks as "the
        // farm is handled" left the agent waiting on two plants instead
        // of sowing a field.
        if (enoughPlanted()) {
            phase = Phase.GROWING;
        }
        status = ActionStatus.RUNNING;
        McAgent.LOGGER.info("[Action] Started: {} (phase {})", title, phase);
    }

    @Override
    public void tick() {
        if (status != ActionStatus.RUNNING) {
            return;
        }
        if (liveCropCount.getAsInt() >= baseline + targetCount) {
            navigation.stop();
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Verified success: {} (inventory {})",
                    title, liveCropCount.getAsInt());
            return;
        }
        if (++totalTicks > TOTAL_TIMEOUT_TICKS) {
            navigation.stop();
            fail("farming made no progress within "
                    + (TOTAL_TIMEOUT_TICKS / 20 / 60) + " minutes");
            return;
        }
        phaseTicks++;
        switch (phase) {
            case TILLING -> tillGround();
            case SOWING -> sowSeed();
            case GROWING -> growCrops();
            case HARVESTING -> reap();
        }
    }

    private void tillGround() {
        if (plot == null) {
            plot = farmer.tillableSpot(RADIUS).orElse(null);
        }
        if (plot == null) {
            noGround = true;
            fail("no tillable ground beside water within " + RADIUS + " blocks");
            return;
        }
        // Till a whole field in one go. Tilling a single block left the
        // sowing phase with nowhere to put a second seed, so the farm
        // only ever grew one crop.
        int tilled = farmer.tillPlot(plot, PLOT_RADIUS);
        if (tilled > 0) {
            McAgent.LOGGER.info("[Action] Tilled {} blocks of farmland", tilled);
            enter(Phase.SOWING);
            return;
        }
        if (phaseTicks > PHASE_TIMEOUT_TICKS) {
            fail("could not till ground (a hoe is needed)");
        }
    }

    private void sowSeed() {
        // Fill the whole field, not one square: every tilled block gets a
        // seed while seeds last.
        sown += farmer.sowAll(seedItemId, PLOT_RADIUS);
        if (sown > 0) {
            McAgent.LOGGER.info("[Action] Sowed {} {}", sown,
                    seedItemId.replaceFirst("^minecraft:", ""));
            enter(Phase.GROWING);
            return;
        }
        if (farmer.cropsPlanted(cropBlockId, RADIUS) > 0) {
            // Nowhere left to sow but the field is not empty: tend what
            // is already growing rather than calling this a failure.
            enter(Phase.GROWING);
            return;
        }
        plot = null; // nothing planted yet; look for fresh ground
        if (phaseTicks > PHASE_TIMEOUT_TICKS) {
            fail("no " + seedItemId.replaceFirst("^minecraft:", "")
                    + " to sow, or nowhere to sow it");
        }
    }

    private void growCrops() {
        if (farmer.cropsRipe(cropBlockId, RADIUS) > 0) {
            enter(Phase.HARVESTING);
            return;
        }
        if (farmer.cropsPlanted(cropBlockId, RADIUS) <= 0) {
            // Nothing growing; go back to preparing ground.
            enter(Phase.TILLING);
            return;
        }
        // Bone meal skips the wait when we have it; otherwise the world
        // clock decides and this simply takes minutes.
        farmer.hurryGrowth(cropBlockId, RADIUS);
        if (phaseTicks > GROW_TIMEOUT_TICKS) {
            fail("crops did not ripen within " + (GROW_TIMEOUT_TICKS / 20 / 60)
                    + " minutes");
        }
    }

    private void reap() {
        int cut = farmer.harvestRipe(cropBlockId, RADIUS);
        if (cut > 0) {
            McAgent.LOGGER.info("[Action] Harvested {} {}", cut,
                    cropBlockId.replaceFirst("^minecraft:", ""));
        }
        // Drops need a moment to reach the inventory; success is judged
        // by the live count at the top of tick().
        if (phaseTicks > PHASE_TIMEOUT_TICKS) {
            // More may still be growing - go round again rather than fail.
            enter(farmer.cropsPlanted(cropBlockId, RADIUS) > 0
                    ? Phase.GROWING : Phase.TILLING);
        }
    }

    /**
     * Enough crops standing to fill the order. One ripe plant yields
     * roughly one of its item, so the target doubles as a plant count.
     */
    private boolean enoughPlanted() {
        return farmer.cropsPlanted(cropBlockId, RADIUS) >= targetCount;
    }

    private void enter(Phase next) {
        if (++phaseChanges > MAX_PHASE_CHANGES) {
            navigation.stop();
            fail("stopped making progress (cycled between " + phase + " and "
                    + next + "); tilling and sowing report success but no crop"
                    + " is taking hold here");
            return;
        }
        phase = next;
        phaseTicks = 0;
        McAgent.LOGGER.info("[Action] {} -> {}", title, next);
    }

    @Override
    public void cancel() {
        if (!status.terminal()) {
            navigation.stop();
            status = ActionStatus.CANCELLED;
        }
    }

    /** Nowhere to farm is a fact about the ground, not bad luck. */
    @Override
    public boolean retryable() {
        return !noGround;
    }

    private void fail(String reason) {
        failureReason = reason;
        status = ActionStatus.FAILED;
        McAgent.LOGGER.warn("[Recovery] Action failed: {} ({})", title, reason);
    }
}
