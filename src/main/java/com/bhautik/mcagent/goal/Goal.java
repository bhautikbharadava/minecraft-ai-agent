package com.bhautik.mcagent.goal;

public sealed interface Goal permits Goal.GetItemGoal {
    record GetItemGoal(String itemId, int count) implements Goal {
    }
}
