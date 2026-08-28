package com.bhautik.mcagent.action;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.integration.BaritoneIntegration;

/**
 * Grows a herd before harvesting it. Walks to two adults, feeds each,
 * and verifies a calf actually arrived by watching the live population
 * count — never by trusting that the feed calls landed.
 *
 * <p>This is what makes leather renewable: hunting alone strips the
 * local herd and pushes every future hunt further from home.
 */
public final class BreedAction implements AgentAction {

    /** Reach for handing an animal food. */
    public static final double FEED_REACH = 3.5;
    /** How far to look for animals to pair up. */
    public static final int SEARCH_RADIUS = 32;
    /** Ticks allowed before the pairing is abandoned. */
    public static final int TIMEOUT_TICKS = 20 * 60;
    /** Adults needed before breeding is even possible. */
    public static final int PAIR = 2;

    private final String title;
    private final String mobId;
    private final String foodItemId;
    private final int targetGrowth;
    private final Hunter hunter;
    private final Breeder breeder;
    private final BaritoneIntegration navigation;

    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private int baselineHerd;
    private int fedThisPair;
    private int ticks;
    private Hunter.MobSite lastGoal;

    public BreedAction(String mobId, String foodItemId, int targetGrowth,
                       Hunter hunter, Breeder breeder,
                       BaritoneIntegration navigation) {
        this.mobId = mobId;
        this.foodItemId = foodItemId;
        this.targetGrowth = targetGrowth;
        this.hunter = hunter;
        this.breeder = breeder;
        this.navigation = navigation;
        this.title = "Breed " + targetGrowth + " "
                + mobId.replaceFirst("^minecraft:", "")
                + " with " + foodItemId.replaceFirst("^minecraft:", "");
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
        baselineHerd = hunter.countNearby(mobId, SEARCH_RADIUS);
        if (baselineHerd < PAIR) {
            fail("need " + PAIR + " " + shortName(mobId) + " to breed, found "
                    + baselineHerd);
            return;
        }
        status = ActionStatus.RUNNING;
        McAgent.LOGGER.info("[Action] Started: {} (herd {})", title, baselineHerd);
    }

    @Override
    public void tick() {
        if (status != ActionStatus.RUNNING) {
            return;
        }
        int herd = hunter.countNearby(mobId, SEARCH_RADIUS);
        if (herd >= baselineHerd + targetGrowth) {
            navigation.stop();
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Verified success: {} (herd {})", title, herd);
            return;
        }
        ticks++;
        if (ticks > TIMEOUT_TICKS) {
            navigation.stop();
            fail("no calf after " + (TIMEOUT_TICKS / 20) + "s"
                    + " (needs " + foodItemId.replaceFirst("^minecraft:", "")
                    + " and two adults in range)");
            return;
        }
        var mate = hunter.nearest(mobId, SEARCH_RADIUS).orElse(null);
        if (mate == null) {
            fail("herd wandered out of range");
            return;
        }
        if (breeder.feed(mobId, foodItemId, FEED_REACH)) {
            fedThisPair++;
            McAgent.LOGGER.info("[Action] Fed {} ({}/{})", shortName(mobId),
                    fedThisPair, PAIR);
            return;
        }
        // Out of reach: close the distance and try again next tick.
        pathTo(mate);
    }

    @Override
    public void cancel() {
        if (!status.terminal()) {
            navigation.stop();
            status = ActionStatus.CANCELLED;
        }
    }

    /**
     * Breeding is an optimisation, not the goal: if it cannot happen the
     * plan should still try to hunt what is already there.
     */
    @Override
    public boolean bestEffort() {
        return true;
    }

    private void pathTo(Hunter.MobSite site) {
        if (lastGoal != null && lastGoal.equals(site)) {
            return;
        }
        lastGoal = site;
        navigation.startGoTo(site.x(), site.y(), site.z());
    }

    private static String shortName(String id) {
        return id.replaceFirst("^minecraft:", "");
    }

    private void fail(String reason) {
        failureReason = reason;
        status = ActionStatus.FAILED;
        McAgent.LOGGER.warn("[Recovery] Action failed: {} ({})", title, reason);
    }
}
