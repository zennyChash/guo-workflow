package com.guo.workflow.contract;

import java.util.Arrays;
import java.util.Optional;

/**
 * Declares the business intent of an action independently from its implementation.
 */
public enum ActionType {
    /**
     * Pre-commit validation. Validation actions must run synchronously during
     * {@link Phase#BEFORE_COMMIT} and produce a boolean validation result.
     */
    VALIDATE("validate"),

    /**
     * Business task. A job may be synchronous or asynchronous and may run before
     * or after the workflow transaction commits.
     */
    JOB("job");

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

