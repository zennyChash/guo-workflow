package com.guo.workflow.contract;

import java.util.Arrays;
import java.util.Optional;

/**
 * Lifecycle events exposed by the workflow action extension.
 */
public enum ActionEvent {
    COMPLETE("complete"),
    ROLLBACK("rollback");

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

