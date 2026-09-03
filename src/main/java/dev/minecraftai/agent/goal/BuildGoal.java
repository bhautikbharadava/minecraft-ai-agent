package dev.minecraftai.agent.goal;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Raise a structure from a blueprint. Framework-free: completion is a
 * live check that the world matches the design, which the build action
 * performs block by block.
 */
public final class BuildGoal implements AgentGoal {

    private final String structure;
    private final BooleanSupplier standing;
    private final IntSupplier blockCount;

    private GoalStatus status = GoalStatus.IDLE;
    private String failureReason;

    public BuildGoal(String structure, BooleanSupplier standing,
                     IntSupplier blockCount) {
        this.structure = structure;
        this.standing = standing;
        this.blockCount = blockCount;
    }

    @Override
    public String title() {
        return "Build " + structure;
    }

    @Override
    public GoalStatus status() {
        return status;
    }

    @Override
    public void activate() {
        status = standing.getAsBoolean() ? GoalStatus.SUCCESS : GoalStatus.ACTIVE;
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
        report.append("Blocks: ").append(blockCount.getAsInt());
        if (failureReason != null) {
            report.append('\n').append("Reason: ").append(failureReason);
        }
        return report.toString();
    }
}
