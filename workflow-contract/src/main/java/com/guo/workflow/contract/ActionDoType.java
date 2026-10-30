package com.guo.workflow.contract;

import java.util.Arrays;
import java.util.Optional;

/**
 * Declares the implementation mechanism used to execute an action.
 */
public enum ActionDoType {
    SQL("sql"),
    SCRIPT("script"),
    STORED_PROC("stored_proc"),
    MESSAGE("message"),
    EXPORT("export"),
    BUSINESS("business");

    private final String value;

    ActionDoType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<ActionDoType> fromValue(String value) {
        return Arrays.stream(values())
                .filter(doType -> doType.value.equalsIgnoreCase(value))
                .findFirst();
    }
}
