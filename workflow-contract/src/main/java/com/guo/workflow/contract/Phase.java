package com.guo.workflow.contract;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Declares when an action runs relative to the workflow transaction commit.
 */
public enum Phase {
    BEFORE_COMMIT("before-commit"),
    AFTER_COMMIT("after-commit");

    private final String value;

    Phase(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<Phase> fromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
        return Arrays.stream(values())
                .filter(phase -> phase.value.equals(normalized))
                .findFirst();
    }
}
