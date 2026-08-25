package dev.minecraftai.agent.goal;

public interface AgentGoal {
    String title();

    GoalStatus status();

    String progressReport();

    void activate();

    void cancel();

    /** Marks an ACTIVE goal as SUCCESS once execution verified reality. */
    void markSuccess();

    /** Marks an ACTIVE goal as FAILED with a reason for the report. */
    void markFailed(String reason);

    /** Human-readable failure reason when status() is FAILED, else null. */
    String failureReason();
}
