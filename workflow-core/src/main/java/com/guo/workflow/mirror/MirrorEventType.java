package com.guo.workflow.mirror;

enum MirrorEventType {
    PROCESS_STARTED("START", "PROCESS"),
    TASK_CREATED("TASK_CREATED", "TASK"),
    TASK_ASSIGNED("ASSIGNMENT", "TASK"),
    TASK_COMPLETED("COMPLETE", "TASK"),
    TASK_DELETED("DELETE", "TASK"),
    NODE_COMPLETED("COMPLETE", "NODE"),
    PROCESS_COMPLETED("END", "PROCESS"),
    PROCESS_CANCELLED("CANCEL", "PROCESS"),
    PROCESS_TERMINATED("TERMINATE", "PROCESS");

    private final String historyEvent;
    private final String scope;

    MirrorEventType(String historyEvent, String scope) {
        this.historyEvent = historyEvent;
        this.scope = scope;
    }

    String historyEvent() {
        return historyEvent;
    }

    String scope() {
        return scope;
    }
}
