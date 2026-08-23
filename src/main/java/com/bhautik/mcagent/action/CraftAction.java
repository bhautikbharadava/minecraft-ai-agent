package com.bhautik.mcagent.action;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.crafting.RecipeResolver.CraftableRecipe;

import java.util.function.IntSupplier;

/**
 * Crafts a batch of items in the agent's inventory grid. Each tick it
 * performs at most one craft and verifies progress against the live
 * inventory count of the output item.
 */
public final class CraftAction implements AgentAction {
    /** Ticks without output progress after all crafts were issued before failing. */
    public static final int IDLE_TIMEOUT_TICKS = 100;

    /** Performs real grid crafting; returns the number of crafts completed. */
    public interface Crafter {
        int craft(CraftableRecipe recipe, int times);
    }

    private final CraftableRecipe recipe;
    private final int craftsNeeded;
    private final IntSupplier liveOutputCount;
    private final Crafter crafter;

    private final String title;
    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private int issued;
    private int failStreak;
    private int idleAfterIssue;
    private int stepTarget;

    public CraftAction(CraftableRecipe recipe, int craftsNeeded, IntSupplier liveOutputCount,
                       Crafter crafter) {
        this.recipe = recipe;
        this.craftsNeeded = craftsNeeded;
        this.liveOutputCount = liveOutputCount;
        this.crafter = crafter;
        this.title = "Craft " + (craftsNeeded * recipe.resultCount()) + " "
                + recipe.resultItemId().replaceFirst("^minecraft:", "");
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
        stepTarget = Math.max(liveOutputCount.getAsInt(), 0) + craftsNeeded * recipe.resultCount();
        McAgent.LOGGER.info("[Action] Started: {}", title);
    }

    @Override
    public void tick() {
        if (status != ActionStatus.RUNNING) {
            return;
        }
        int current = Math.max(liveOutputCount.getAsInt(), 0);
        if (current >= stepTarget) {
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Verified success: {} (inventory {})", title, current);
            return;
        }
        if (issued < craftsNeeded) {
            int completed = crafter.craft(recipe, 1);
            issued += completed;
            if (completed <= 0) {
                failStreak++;
                if (failStreak >= 20) {
                    fail("crafting refused by game");
                }
            } else {
                failStreak = 0;
            }
            return;
        }
        idleAfterIssue++;
        if (idleAfterIssue > IDLE_TIMEOUT_TICKS) {
            fail("crafted output never appeared in inventory");
        }
    }

    @Override
    public void cancel() {
        if (!status.terminal()) {
            status = ActionStatus.CANCELLED;
        }
    }

    private void fail(String reason) {
        failureReason = reason;
        status = ActionStatus.FAILED;
        McAgent.LOGGER.warn("[Recovery] Action failed: {} ({})", title, reason);
    }
}
