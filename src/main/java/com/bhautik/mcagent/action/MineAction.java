package com.bhautik.mcagent.action;

import com.bhautik.mcagent.integration.BaritoneIntegration;
import com.bhautik.mcagent.McAgent;

import java.util.function.IntSupplier;

/**
 * Mines until the live inventory count of the target item reaches the
 * requested total. Progress is measured independently from the backend:
 * the action only trusts the inventory count supplier.
 */
public final class MineAction implements AgentAction {
    /** Ticks without inventory progress before the backend is re-issued or the action fails. */
    public static final int IDLE_TIMEOUT_TICKS = 600;
    public static final int MAX_ISSUE_ATTEMPTS = 2;
    /**
     * Most extra ore taken from a vein once the goal is already met.
     * Bounded so a rich deposit cannot turn "mine 2" into an endless dig.
     */
    public static final int MAX_VEIN_BONUS = 12;
    private final String title;
    private final String sourceBlockName;
    private final int targetTotal;
    private final IntSupplier liveCount;
    private final BaritoneIntegration backend;
    private final String preferredToolItemId;
    private final Equipper equipper;
    private final com.bhautik.mcagent.world.PositionAnchor anchor;
    /** Same-ore blocks still exposed nearby; null disables vein mining. */
    private final IntSupplier nearbyOreCount;

    private int veinBonus;
    private int veinTarget;
    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private int issuesUsed;
    private boolean equipped;
    private int ticksSinceStart;
    private int lastCount;
    private int idleTicks;
    private int lastAnchorX = Integer.MIN_VALUE;
    private int lastAnchorZ = Integer.MIN_VALUE;
    private int lastAnchorY = Integer.MIN_VALUE;

    public MineAction(String sourceBlockName, int baselineCount, int targetTotal,
                      IntSupplier liveCount, BaritoneIntegration backend) {
        this(sourceBlockName, baselineCount, targetTotal, liveCount, backend,
                null, null, null);
    }

    public MineAction(String sourceBlockName, int baselineCount, int targetTotal,
                      IntSupplier liveCount, BaritoneIntegration backend,
                      String preferredToolItemId) {
        this(sourceBlockName, baselineCount, targetTotal, liveCount, backend,
                preferredToolItemId, null, null);
    }

    public MineAction(String sourceBlockName, int baselineCount, int targetTotal,
                      IntSupplier liveCount, BaritoneIntegration backend,
                      String preferredToolItemId,
                      com.bhautik.mcagent.world.PositionAnchor anchor,
                      Equipper equipper) {
        this(sourceBlockName, baselineCount, targetTotal, liveCount, backend,
                preferredToolItemId, anchor, equipper, null);
    }

    public MineAction(String sourceBlockName, int baselineCount, int targetTotal,
                      IntSupplier liveCount, BaritoneIntegration backend,
                      String preferredToolItemId,
                      com.bhautik.mcagent.world.PositionAnchor anchor,
                      Equipper equipper,
                      IntSupplier nearbyOreCount) {
        this.nearbyOreCount = nearbyOreCount;
        this.sourceBlockName = sourceBlockName;
        this.targetTotal = targetTotal;
        this.liveCount = liveCount;
        this.backend = backend;
        this.preferredToolItemId = preferredToolItemId;
        this.equipper = equipper;
        this.anchor = anchor;
        this.title = "Mine " + (targetTotal - baselineCount) + " "
                + sourceBlockName.replaceFirst("^minecraft:", "");
        this.lastCount = baselineCount;
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
        status = ActionStatus.RUNNING;
        issue();
    }

    @Override
    public void tick() {
        if (status != ActionStatus.RUNNING) {
            return;
        }
        ticksSinceStart++;
        int count = Math.max(liveCount.getAsInt(), 0);
        if (count >= targetTotal) {
            // Ore generates in veins, and the walk there is the expensive
            // part. If more of the same ore is still exposed within reach,
            // finish the vein instead of leaving it for another trip.
            if (finishVein(count)) {
                return;
            }
            backend.stop();
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Verified success: {} (inventory {}{})",
                    title, count, veinBonus > 0 ? ", +" + veinBonus + " from vein" : "");
            return;
        }
        // Traveling toward the target IS progress: searching far veins
        // legitimately takes minutes, and restarting Baritone mid-scan
        // only resets the block being broken.
        if (anchor != null && anchorMoved(anchor)) {
            idleTicks = 0;
        }
        if (count > lastCount) {
            lastCount = count;
            idleTicks = 0;
            return;
        }
        idleTicks++;
        if (idleTicks < IDLE_TIMEOUT_TICKS) {
            return;
        }
        if (issuesUsed < MAX_ISSUE_ATTEMPTS) {
            McAgent.LOGGER.info("[Recovery] No progress on [{}], re-issuing mining request", title);
            idleTicks = 0;
            issue();
            return;
        }
        fail("mining made no progress for " + (IDLE_TIMEOUT_TICKS / 20) + "s");
    }

    @Override
    public void pause() {
        backend.stop();
    }

    @Override
    public void cancel() {
        if (!status.terminal()) {
            backend.stop();
            status = ActionStatus.CANCELLED;
        }
    }

    /**
     * Keeps mining while the vein that satisfied the goal still has ore
     * exposed nearby. Bounded by {@link #MAX_VEIN_BONUS} so a huge
     * deposit cannot turn a small request into an endless dig, and by
     * the caller supplying no scanner at all (then this never engages).
     *
     * @return true when the action should keep running
     */
    private boolean finishVein(int count) {
        if (nearbyOreCount == null || veinBonus >= MAX_VEIN_BONUS) {
            return false;
        }
        int exposed = Math.max(nearbyOreCount.getAsInt(), 0);
        if (exposed <= 0) {
            return false;
        }
        if (veinTarget <= count) {
            // Take what is exposed, capped, and keep the existing mine
            // order running toward the raised target.
            int extra = Math.min(exposed, MAX_VEIN_BONUS - veinBonus);
            veinBonus += extra;
            veinTarget = count + extra;
            McAgent.LOGGER.info("[Action] Vein continues ({} more {} exposed); mining on",
                    extra, sourceBlockName.replaceFirst("^minecraft:", ""));
            issue(veinTarget);
        }
        return count < veinTarget;
    }

    private void issue() {
        issue(targetTotal);
    }

    private void issue(int target) {
        issuesUsed++;
        lastCount = Math.max(liveCount.getAsInt(), 0);
        int missing = target - lastCount;
        if (missing <= 0) {
            status = ActionStatus.SUCCESS;
            return;
        }
        McAgent.LOGGER.info("[Action] Started: {}", title);
        if (preferredToolItemId != null && equipper != null && !equipped) {
            equipped = equipper.equip(preferredToolItemId);
            if (!equipped) {
                // Best-effort: mining proceeds, but wrong-tier breaks may
                // drop nothing and the idle timeout will surface it honestly.
                McAgent.LOGGER.warn("[Recovery] Could not equip {} for {}",
                        preferredToolItemId.replaceFirst("^minecraft:", ""), title);
            }
        }
        if (!backend.startMine(sourceBlockName, missing)) {
            fail("no navigation backend available: " + backend.describe());
        }
    }


    private boolean anchorMoved(com.bhautik.mcagent.world.PositionAnchor anchor) {
        int ax = anchor.x();
        int az = anchor.z();
        int ay = anchor.y();
        boolean moved = lastAnchorX != Integer.MIN_VALUE
                && (ax != lastAnchorX || az != lastAnchorZ
                    || (ay != Integer.MIN_VALUE && lastAnchorY != Integer.MIN_VALUE
                        && ay != lastAnchorY));
        lastAnchorX = ax;
        lastAnchorZ = az;
        lastAnchorY = ay;
        return moved;
    }

    private void fail(String reason) {
        backend.stop();
        failureReason = reason;
        status = ActionStatus.FAILED;
        McAgent.LOGGER.warn("[Recovery] Action failed: {} ({})", title, reason);
    }
}
