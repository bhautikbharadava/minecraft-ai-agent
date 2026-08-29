package dev.minecraftai.agent.goal;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Enchant a carried item. Framework-free: completion is a live check
 * that the agent now carries an enchanted copy, so the goal never
 * trusts an action's own claim of success.
 */
public final class EnchantGoal implements AgentGoal {

    private final String itemDisplay;
    private final int minLevel;
    private final BooleanSupplier enchanted;
    private final IntSupplier xpLevel;

    private GoalStatus status = GoalStatus.IDLE;
    private String failureReason;

    public EnchantGoal(String itemDisplay, int minLevel, BooleanSupplier enchanted,
                       IntSupplier xpLevel) {
        this.itemDisplay = itemDisplay;
        this.minLevel = minLevel;
        this.enchanted = enchanted;
        this.xpLevel = xpLevel;
    }

    public boolean satisfied() {
        return enchanted.getAsBoolean();
    }

    @Override
    public String title() {
        return "Enchant " + itemDisplay
                + (minLevel > 1 ? " (level " + minLevel + "+)" : "");
    }

    @Override
    public GoalStatus status() {
        return status;
    }

    @Override
    public void activate() {
        if (satisfied()) {
            status = GoalStatus.SUCCESS;
            return;
        }
        status = GoalStatus.ACTIVE;
    }

    @Override
    public void cancel() {
        if (status == GoalStatus.ACTIVE || status == GoalStatus.IDLE) {
            status = GoalStatus.CANCELLED;
        }
    }

    @Override
    public void markSuccess() {
        status = GoalStatus.SUCCESS;
    }

    @Override
    public void markFailed(String reason) {
        status = GoalStatus.FAILED;
        this.failureReason = reason;
    }

    @Override
    public String failureReason() {
        return failureReason;
    }

    @Override
    public String progressReport() {
        StringBuilder report = new StringBuilder();
        report.append("Goal: ").append(title()).append('\n');
        report.append("Status: ").append(status).append('\n');
        report.append("Enchanted: ").append(satisfied() ? "yes" : "not yet").append('\n');
        report.append("XP level: ").append(xpLevel.getAsInt());
        if (failureReason != null) {
            report.append('\n').append("Reason: ").append(failureReason);
        }
        return report.toString();
    }
}
