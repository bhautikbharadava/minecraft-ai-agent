package dev.minecraftai.agent.goal;

import dev.minecraftai.agent.item.MinecraftItem;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Composite get-goal (PRD UC-09): satisfied only when every piece of
 * the kit is carried. Arrival of pieces updates the live report.
 */
public final class GetKitGoal implements AgentGoal {
    private final String kitName;
    private final int requestedEach;
    private final List<MinecraftItem> pieces;
    private final BooleanSupplier allCarried;
    private final java.util.function.Function<MinecraftItem, Integer> liveCount;
    private GoalStatus status = GoalStatus.IDLE;
    private String failureReason;

    public GetKitGoal(String kitName, List<MinecraftItem> pieces, int requestedEach,
                      BooleanSupplier allCarried) {
        this(kitName, pieces, requestedEach, allCarried, item -> 0);
    }

    public GetKitGoal(String kitName, List<MinecraftItem> pieces, int requestedEach,
                      BooleanSupplier allCarried,
                      java.util.function.Function<MinecraftItem, Integer> liveCount) {
        this.kitName = kitName;
        this.pieces = List.copyOf(pieces);
        this.requestedEach = requestedEach;
        this.allCarried = allCarried;
        this.liveCount = liveCount;
    }

    public String kitName() {
        return kitName;
    }

    public List<MinecraftItem> pieces() {
        return pieces;
    }

    public int requestedEach() {
        return requestedEach;
    }

    @Override
    public String title() {
        return "Get " + kitName.replace('_', ' ');
    }

    @Override
    public GoalStatus status() {
        return status;
    }

    @Override
    public void activate() {
        status = allCarried.getAsBoolean() ? GoalStatus.SUCCESS : GoalStatus.ACTIVE;
    }

    @Override
    public void cancel() {
        if (status == GoalStatus.ACTIVE) {
            status = GoalStatus.CANCELLED;
        }
    }

    /** Marks an ACTIVE goal as SUCCESS once every piece verified. */
    public void markSuccess() {
        if (status == GoalStatus.ACTIVE && allCarried.getAsBoolean()) {
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
        StringBuilder report = new StringBuilder("Goal: " + title());
        for (MinecraftItem piece : pieces) {
            int have = liveCount.apply(piece);
            String state = have >= requestedEach ? "done (" + have + "/" + requestedEach + ")"
                    : have + "/" + requestedEach + " pending";
            report.append(System.lineSeparator()).append("  - ")
                    .append(piece.displayName()).append(": ").append(state);
        }
        report.append(System.lineSeparator()).append("Status: ").append(status);
        if (failureReason != null) {
            report.append(System.lineSeparator()).append("Reason: ").append(failureReason);
        }
        return report.toString();
    }
}
