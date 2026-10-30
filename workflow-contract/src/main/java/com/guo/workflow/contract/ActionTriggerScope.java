package com.guo.workflow.contract;

import java.util.Arrays;
import java.util.Optional;

/**
 * Identifies the Flowable lifecycle level that triggers an action.
 */
public enum ActionTriggerScope {
    TASK("task"),
    NODE("node"),
    PROCESS("process");

    private final String value;

    ActionTriggerScope(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<ActionTriggerScope> fromValue(String value) {
        return Arrays.stream(values())
                .filter(scope -> scope.value.equalsIgnoreCase(value))
                .findFirst();
    }
}
