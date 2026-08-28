package com.bhautik.mcagent.action;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.integration.BaritoneIntegration;
import com.bhautik.mcagent.world.PositionAnchor;
import com.bhautik.mcagent.world.XpSensor;

/**
 * Mines XP-bearing ore until the agent reaches a target experience
 * level. Vanilla grants the orbs on break, so this action only has to
 * keep mining and watch the level climb.
 *
 * <p>Honest about the grind: ore XP is small (a coal ore yields 0-2
 * points, and level 30 is 1395 points), so the action gives up with a
 * clear progress report rather than digging forever. Reaching level 30
 * by mining alone is not realistic — that needs a mob grinder, which is
 * a separate milestone.
 */
public final class XpFarmAction implements AgentAction {

    /** Ticks without a level gain before the farm is abandoned. */
    public static final int IDLE_TIMEOUT_TICKS = 20 * 120;
    /** Hard ceiling so a hopeless target cannot stall a goal forever. */
    public static final int MAX_TICKS = 20 * 60 * 10;

    private final String title;
    private final String oreBlock;
    private final int targetLevel;
    private final XpSensor xp;
    private final BaritoneIntegration navigation;
    private final PositionAnchor anchor;

    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private int lastLevel;
    private int idleTicks;
    private int totalTicks;
    private boolean issued;

    public XpFarmAction(String oreBlock, int targetLevel, XpSensor xp,
                        BaritoneIntegration navigation, PositionAnchor anchor) {
        this.oreBlock = oreBlock;
        this.targetLevel = targetLevel;
        this.xp = xp;
        this.navigation = navigation;
        this.anchor = anchor;
        this.title = "Farm XP to level " + targetLevel
                + " (" + oreBlock.replaceFirst("^minecraft:", "") + ")";
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
        lastLevel = xp.level();
        if (lastLevel >= targetLevel) {
            status = ActionStatus.SUCCESS;
            return;
        }
        if (!navigation.available()) {
            fail("no navigation backend for XP mining");
            return;
        }
        status = ActionStatus.RUNNING;
        McAgent.LOGGER.info("[Action] Started: {} (from level {})", title, lastLevel);
    }

    @Override
    public void tick() {
        if (status != ActionStatus.RUNNING) {
            return;
        }
        int level = xp.level();
        if (level >= targetLevel) {
            navigation.stop();
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Verified success: {} (level {})", title, level);
            return;
        }
        if (!issued) {
            // One open-ended mine order; vanilla drops the orbs as blocks break.
            navigation.startMine(oreBlock, Integer.MAX_VALUE);
            issued = true;
        }
        totalTicks++;
        if (level > lastLevel) {
            lastLevel = level;
            idleTicks = 0;
        } else {
            idleTicks++;
        }
        if (idleTicks > IDLE_TIMEOUT_TICKS || totalTicks > MAX_TICKS) {
            navigation.stop();
            fail("reached level " + level + " of " + targetLevel
                    + " before giving up (ore XP is slow; a mob grinder is the"
                    + " realistic route to high levels)");
        }
    }

    @Override
    public void cancel() {
        if (!status.terminal()) {
            navigation.stop();
            status = ActionStatus.CANCELLED;
        }
    }

    /** Anchor is kept for parity with MineAction's re-centering behavior. */
    public PositionAnchor anchor() {
        return anchor;
    }

    private void fail(String reason) {
        failureReason = reason;
        status = ActionStatus.FAILED;
        McAgent.LOGGER.warn("[Recovery] Action failed: {} ({})", title, reason);
    }
}
