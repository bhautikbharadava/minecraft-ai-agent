package com.bhautik.mcagent.action;

import com.bhautik.mcagent.McAgent;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Smelts a batch of items through a real, nearby furnace. The furnace
 * cooks on its own block-entity ticks; this action inserts input and
 * fuel once, then harvests finished output every tick and verifies
 * progress against the live inventory count of the result item.
 */
public final class SmeltAction implements AgentAction {
    /** Vanilla cooking duration for one smelt operation (ticks). */
    public static final int COOK_TICKS_PER_ITEM = 200;
    /** Extra ticks allowed before the first item finishes (lit-up time). */
    public static final int WARMUP_TICKS = 200;
    /** Failed insertion attempts before giving up. */
    public static final int MAX_ISSUE_ATTEMPTS = 20;
    /** Items one unit of the standard fuel (coal) smelts. */
    public static final int ITEMS_PER_FUEL = 8;

    /** Performs real furnace interaction against a nearby furnace. */
    public interface Smelter {
        Result begin(String inputItemId, int inputCount, String fuelItemId, int fuelCount);

        Result harvest();

        record Result(boolean success, String failureReason) {
            public static Result ok() {
                return new Result(true, null);
            }

            public static Result failed(String reason) {
                return new Result(false, reason);
            }
        }
    }

    private final String title;
    private final String inputItemId;
    private final String fuelItemId;
    private final int smeltsNeeded;
    private final IntSupplier liveOutputCount;
    private final Smelter smelter;
    private final BooleanSupplier furnaceInRange;

    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private boolean begun;
    private int attempts;
    private int failStreak;
    private int lastCount;
    private int idleTicks;
    private int timeoutTicks;

    public SmeltAction(String inputItemId, String fuelItemId, int smeltsNeeded,
                       IntSupplier liveOutputCount, Smelter smelter,
                       BooleanSupplier furnaceInRange) {
        this.inputItemId = inputItemId;
        this.fuelItemId = fuelItemId;
        this.smeltsNeeded = smeltsNeeded;
        this.liveOutputCount = liveOutputCount;
        this.smelter = smelter;
        this.furnaceInRange = furnaceInRange;
        this.title = "Smelt " + smeltsNeeded + " "
                + inputItemId.replaceFirst("^minecraft:", "");
        this.lastCount = Math.max(liveOutputCount.getAsInt(), 0);
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
        if (!furnaceInRange.getAsBoolean()) {
            fail("furnace is not within range");
            return;
        }
        status = ActionStatus.RUNNING;
        lastCount = Math.max(liveOutputCount.getAsInt(), 0);
        timeoutTicks = WARMUP_TICKS + smeltsNeeded * COOK_TICKS_PER_ITEM;
        McAgent.LOGGER.info("[Action] Started: {}", title);
    }

    @Override
    public void tick() {
        if (status != ActionStatus.RUNNING) {
            return;
        }
        int count = Math.max(liveOutputCount.getAsInt(), 0);
        if (count >= lastCount + smeltsNeeded) {
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Verified success: {} (inventory {})", title, count);
            return;
        }
        if (!begun) {
            // Loading is the only step that strictly needs proximity.
            if (!furnaceInRange.getAsBoolean()) {
                fail("furnace is not within range");
                return;
            }
            issue();
            return;
        }
        // The furnace cooks autonomously; drifting out of range must not
        // abort the run. Harvest opportunistically whenever we are close,
        // and judge completion purely by live inventory.
        if (furnaceInRange.getAsBoolean()) {
            smelter.harvest();
        }
        if (count > lastCount) {
            lastCount = count;
            idleTicks = 0;
            return;
        }
        idleTicks++;
        if (idleTicks > timeoutTicks) {
            fail("furnace produced no output within " + (timeoutTicks / 20) + "s");
        }
    }

    @Override
    public void cancel() {
        if (!status.terminal()) {
            status = ActionStatus.CANCELLED;
        }
    }

    /** Inserts input + fuel into the furnace; retries briefly on failure. */
    private void issue() {
        int remaining = lastCount + smeltsNeeded
                - Math.max(liveOutputCount.getAsInt(), 0);
        int fuelCount = ceilDiv(remaining, ITEMS_PER_FUEL);
        var result = smelter.begin(inputItemId, remaining, fuelItemId, fuelCount);
        attempts++;
        if (result.success()) {
            begun = true;
            idleTicks = 0;
            McAgent.LOGGER.info("[Action] Furnace loaded: {} x{} (+{} {})",
                    inputItemId.replaceFirst("^minecraft:", ""), remaining,
                    fuelCount, fuelItemId.replaceFirst("^minecraft:", ""));
            return;
        }
        failStreak++;
        if (attempts >= MAX_ISSUE_ATTEMPTS || failStreak >= MAX_ISSUE_ATTEMPTS) {
            fail(result.failureReason());
        }
    }

    private static int ceilDiv(int dividend, int divisor) {
        return (dividend + divisor - 1) / divisor;
    }

    private void fail(String reason) {
        failureReason = reason;
        status = ActionStatus.FAILED;
        McAgent.LOGGER.warn("[Recovery] Action failed: {} ({})", title, reason);
    }
}
