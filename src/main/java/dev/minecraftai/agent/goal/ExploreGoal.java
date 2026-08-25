package dev.minecraftai.agent.goal;

import java.util.function.BooleanSupplier;

/**
 * Goal: reach a named biome (UC-08). Arrival is verified through the
 * supplied check against live world state; the goal itself stays
 * framework-free.
 */
public final class ExploreGoal implements AgentGoal {
    private final String targetDisplay;
    private final BooleanSupplier arrived;
    private GoalStatus status = GoalStatus.IDLE;
    private String failureReason;

    public ExploreGoal(String targetDisplay, BooleanSupplier arrived) {
        this.targetDisplay = targetDisplay;
        this.arrived = arrived;
    }

    @Override
    public String title() {
        return "Explore to " + targetDisplay;
    }

    public String targetDisplay() {
        return targetDisplay;
    }

    /** Live arrival check; also drives immediate-success on activate. */
    public boolean satisfied() {
        return arrived.getAsBoolean();
    }

    @Override
    public GoalStatus status() {
        return status;
    }

    @Override
    public void activate() {
        status = satisfied() ? GoalStatus.SUCCESS : GoalStatus.ACTIVE;
    }

    @Override
    public void cancel() {
        if (status == GoalStatus.ACTIVE) {
            status = GoalStatus.CANCELLED;
        }
    }

    /** Marks an ACTIVE goal as SUCCESS once arrival was verified. */
    public void markSuccess() {
        if (status == GoalStatus.ACTIVE && satisfied()) {
            status = GoalStatus.SUCCESS;
        }
    }

    /** Marks an ACTIVE goal as FAILED with a reason for the report. */
    public void markFailed(String reason) {
        if (status == GoalStatus.ACTIVE) {
            failureReason = reason;
            status = GoalStatus.FAILED;
        }
    }

    public String failureReason() {
        return failureReason;
    }

    @Override
    public String progressReport() {
        StringBuilder report = new StringBuilder(String.join(System.lineSeparator(),
                "Goal: " + title(),
                "Target biome: " + targetDisplay,
                "Status: " + status));
        if (failureReason != null) {
            report.append(System.lineSeparator()).append("Reason: ").append(failureReason);
        }
        return report.toString();
    }
}
