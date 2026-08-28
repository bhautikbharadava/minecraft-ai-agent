package com.bhautik.mcagent.action;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.integration.BaritoneIntegration;

import java.util.function.IntSupplier;

/**
 * Hunts passive animals until the agent carries enough of their drop.
 *
 * <p>Prey moves, so this action re-targets every tick instead of
 * pathing once: it walks toward the nearest animal, strikes when in
 * reach, then walks onto the kill site to pick the drops up. Success is
 * judged purely by the live inventory count, never by kills landed —
 * a cow can drop zero leather.
 */
public final class HuntAction implements AgentAction {

    /** Melee reach for a strike (vanilla survival reach). */
    public static final double STRIKE_REACH = 3.5;
    /** How far the agent will roam looking for prey. */
    public static final int SEARCH_RADIUS = 48;
    /** Re-path only when the target drifted this far from the last goal. */
    public static final double REPATH_DISTANCE = 3.0;
    /** Ticks without an inventory gain before the hunt is abandoned. */
    public static final int IDLE_TIMEOUT_TICKS = 20 * 90;
    /**
     * Ticks allowed to reach a kill site and vacuum up its drops. Long
     * enough to actually walk there — the agent kills at range and the
     * drop can be several blocks away — and cut short the moment the
     * item lands in the inventory.
     */
    public static final int COLLECT_TICKS = 20 * 10;
    /**
     * Ticks spent wandering for prey before giving up. Every other
     * acquisition route travels to its resource (mining walks to veins,
     * biome-gated items chain exploration); hunting roams the same way
     * instead of only killing what happens to be underfoot.
     */
    public static final int SEARCH_TIMEOUT_TICKS = 20 * 120;
    /**
     * Animals always left alive. Killing the last of a herd is what
     * forces every later hunt to walk further from home, so the agent
     * stops at a breeding pair and reports why instead of clearing the
     * area out.
     */
    public static final int BREEDING_STOCK = 2;

    private final String title;
    private final String mobId;
    private final String dropItemId;
    private final int targetCount;
    private final IntSupplier liveDropCount;
    private final Hunter hunter;
    private final BaritoneIntegration navigation;
    private final com.bhautik.mcagent.world.PositionAnchor anchor;

    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private boolean searchExhausted;
    private boolean sparedHerd;
    private boolean collecting;
    private int lastHerd;
    private int baseline;
    private int lastCount;
    private int idleTicks;
    private int collectTicks;
    private int searchTicks;
    private boolean searching;
    private Hunter.MobSite lastGoal;
    private Hunter.MobSite killSite;

    public HuntAction(String mobId, String dropItemId, int targetCount,
                      IntSupplier liveDropCount, Hunter hunter,
                      BaritoneIntegration navigation,
                      com.bhautik.mcagent.world.PositionAnchor anchor) {
        this.mobId = mobId;
        this.dropItemId = dropItemId;
        this.targetCount = targetCount;
        this.liveDropCount = liveDropCount;
        this.hunter = hunter;
        this.navigation = navigation;
        this.anchor = anchor;
        this.title = "Hunt " + targetCount + " "
                + dropItemId.replaceFirst("^minecraft:", "")
                + " from " + mobId.replaceFirst("^minecraft:", "");
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
        baseline = Math.max(liveDropCount.getAsInt(), 0);
        lastCount = baseline;
        lastHerd = hunter.countNearby(mobId, SEARCH_RADIUS);
        // An empty scan is not a failure any more: the agent goes looking.
        status = ActionStatus.RUNNING;
        McAgent.LOGGER.info("[Action] Started: {}", title);
    }

    @Override
    public void tick() {
        if (status != ActionStatus.RUNNING) {
            return;
        }
        int count = Math.max(liveDropCount.getAsInt(), 0);
        if (count >= baseline + targetCount) {
            navigation.stop();
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Verified success: {} (inventory {})", title, count);
            return;
        }
        if (count > lastCount) {
            // Picked something up: reset patience and stop hovering.
            lastCount = count;
            idleTicks = 0;
            killSite = null;
            collectTicks = 0;
            collecting = false;
        } else if (!searching) {
            // Roaming has its own, longer budget; don't let the "gathered
            // nothing" clock cut a legitimate search short.
            idleTicks++;
        }
        if (idleTicks > IDLE_TIMEOUT_TICKS) {
            navigation.stop();
            fail("no " + shortName(dropItemId) + " gathered within "
                    + (IDLE_TIMEOUT_TICKS / 20) + "s");
            return;
        }

        int herd = hunter.countNearby(mobId, SEARCH_RADIUS);
        // A kill just happened: step onto the drop BEFORE chasing the next
        // animal. Waiting until the herd is gone leaves a trail of
        // uncollected leather behind.
        if (herd < lastHerd) {
            collecting = true;
            collectTicks = 0;
            lastGoal = null; // force a fresh path to the drop
        }
        lastHerd = herd;
        if (collecting) {
            if (killSite != null && collectTicks < COLLECT_TICKS) {
                collectTicks++;
                pathTo(killSite);
                return;
            }
            collecting = false;
        }

        // Decide whether there is anything huntable BEFORE touching search
        // state: prey that must be spared is not a reason to stop roaming,
        // and resetting the search clock for it would restart the roam
        // every tick (re-issuing navigation and never timing out).
        var prey = hunter.nearest(mobId, SEARCH_RADIUS).orElse(null);
        boolean spared = prey != null && herd <= BREEDING_STOCK;
        if (spared && !sparedHerd) {
            sparedHerd = true;
            McAgent.LOGGER.info(
                    "[Action] Sparing {} breeding {}; looking elsewhere",
                    BREEDING_STOCK, shortName(mobId));
        }
        if (prey == null || spared) {
            roamForPrey();
            return;
        }

        // Huntable: stop roaming and engage.
        if (searching) {
            searching = false;
            searchTicks = 0;
            lastGoal = null;
            navigation.stop();
        }
        sparedHerd = false;

        if (hunter.strike(mobId, STRIKE_REACH)) {
            // In reach and swinging: remember where drops will land.
            killSite = prey;
            collectTicks = 0;
            return;
        }
        pathTo(prey);
    }

    @Override
    public void cancel() {
        if (!status.terminal()) {
            navigation.stop();
            status = ActionStatus.CANCELLED;
        }
    }

    /**
     * Wanders outward looking for a herd. Gives up only after a real
     * search, and marks the failure non-retryable: if two minutes of
     * roaming found no prey, an identical replan will not either.
     */
    private void roamForPrey() {
        if (!searching) {
            searching = true;
            searchTicks = 0;
            McAgent.LOGGER.info("[Action] No {} in range; roaming to find one",
                    shortName(mobId));
        }
        searchTicks++;
        if (searchTicks == 1) {
            navigation.startExplore(anchor.x(), anchor.z());
        }
        if (searchTicks > SEARCH_TIMEOUT_TICKS) {
            navigation.stop();
            searchExhausted = true;
            fail(sparedHerd
                    ? "only a breeding pair of " + shortName(mobId) + " left nearby;"
                            + " breed them (needs wheat) or hunt further afield"
                    : "no " + shortName(mobId) + " found after roaming for "
                            + (SEARCH_TIMEOUT_TICKS / 20) + "s");
        }
    }

    @Override
    public boolean retryable() {
        // Exhausting the roam is a fact about the world, not bad luck.
        return !searchExhausted;
    }

    /** Re-issues navigation only when the target moved meaningfully. */
    private void pathTo(Hunter.MobSite site) {
        if (lastGoal != null && distance(lastGoal, site) < REPATH_DISTANCE) {
            return;
        }
        lastGoal = site;
        navigation.startGoTo(site.x(), site.y(), site.z());
    }

    private static double distance(Hunter.MobSite from, Hunter.MobSite to) {
        double dx = from.x() - to.x();
        double dy = from.y() - to.y();
        double dz = from.z() - to.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
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
