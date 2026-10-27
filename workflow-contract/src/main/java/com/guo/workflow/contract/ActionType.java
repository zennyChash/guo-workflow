package com.guo.workflow.contract;

import java.util.Arrays;
import java.util.Optional;

/**
 * Selects the business action implementation without conditional dispatch logic.
 */
public enum ActionType {
    VALIDATE("validate"),
    SQL("sql"),
    STORED_PROC("stored_proc"),
    INSTRUCTION("instruction"),
    SCRIPT("script");

    private final String value;

    ActionType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<ActionType> fromValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equalsIgnoreCase(value))
                .findFirst();
    }
}

