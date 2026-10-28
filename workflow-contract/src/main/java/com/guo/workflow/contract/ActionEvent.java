package com.guo.workflow.contract;

import java.util.Arrays;
import java.util.Optional;

/**
 * Flowable lifecycle events exposed by the workflow action extension.
 */
public enum ActionEvent {
    /**
     * Fired when a task is created. It occurs after assignment.
     */
    CREATE("create"),

    /**
     * Fired when a task is assigned. Assignment occurs before task creation.
     */
    ASSIGNMENT("assignment"),

    COMPLETE("complete"),

    /**
     * Fired when a task is deleted, rejected with a jump, or forcibly terminated.
     */
    DELETE("delete"),

    /**
     * Fired when a transition is taken.
     */
    TAKE("take");

    private final String value;

    ActionEvent(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<ActionEvent> fromValue(String value) {
        return Arrays.stream(values())
                .filter(event -> event.value.equalsIgnoreCase(value))
                .findFirst();
    }
}

