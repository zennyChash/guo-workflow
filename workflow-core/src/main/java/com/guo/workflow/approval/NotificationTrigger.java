package com.guo.workflow.approval;

import com.guo.workflow.contract.ActionTriggerScope;
import com.guo.workflow.contract.exception.FlowableActionException;

import java.util.Locale;

/**
 * Business notification trigger translated into action scope and event.
 */
public enum NotificationTrigger {
    ASSIGNMENT(ActionTriggerScope.TASK, "assignment"),
    TASK_COMPLETE(ActionTriggerScope.TASK, "complete"),
    NODE_COMPLETE(ActionTriggerScope.NODE, "complete");

    private final ActionTriggerScope scope;
    private final String event;

    NotificationTrigger(ActionTriggerScope scope, String event) {
        this.scope = scope;
        this.event = event;
    }

    ActionTriggerScope scope() {
        return scope;
    }

    String event() {
        return event;
    }

    static NotificationTrigger fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new FlowableActionException(
                    "guo:notification trigger must not be blank");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "assignment", "todo", "assigned" -> ASSIGNMENT;
            case "submitted", "task_complete", "task-complete", "task-completed" ->
                    TASK_COMPLETE;
            case "node_complete", "node-complete", "node-completed", "completed" ->
                    NODE_COMPLETE;
            default -> throw new FlowableActionException(
                    "Unsupported guo:notification trigger: " + value);
        };
    }
}
