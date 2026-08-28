package com.bhautik.mcagent.state;

public record WorldState(
        float health,
        float maxHealth,
        int hunger,
        double x,
        double y,
        double z,
        String dimension,
        int xpLevel
) {
}
