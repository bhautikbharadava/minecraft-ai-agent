package dev.minecraftai.agent.goal;

import java.util.Optional;

public final class AgentGoalManager {
    private AgentGoal activeGoal;

    public AgentGoal register(AgentGoal goal) {
        goal.activate();
        if (goal.status() == GoalStatus.ACTIVE) {
            activeGoal = goal;
        } else {
            activeGoal = null;
        }
        return goal;
    }

    public Optional<AgentGoal> activeGoal() {
        return Optional.ofNullable(activeGoal);
    }

    public String describeActiveGoal() {
        return activeGoal().map(AgentGoal::progressReport).orElse("No active goal.");
    }

    public String cancelActiveGoal() {
        if (activeGoal == null) {
            return "No active goal to cancel.";
        }
        activeGoal.cancel();
        String report = activeGoal.progressReport();
        activeGoal = null;
        return report;
    }
}
