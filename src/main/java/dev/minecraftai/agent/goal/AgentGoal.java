package dev.minecraftai.agent.goal;

public interface AgentGoal {
    String title();

    GoalStatus status();

    String progressReport();

    void activate();

    void cancel();
}
