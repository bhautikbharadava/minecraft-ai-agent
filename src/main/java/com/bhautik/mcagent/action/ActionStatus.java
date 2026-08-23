package com.bhautik.mcagent.action;

public enum ActionStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }
}
