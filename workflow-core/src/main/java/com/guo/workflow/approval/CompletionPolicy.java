package com.guo.workflow.approval;

import com.guo.workflow.contract.exception.FlowableActionException;

import java.util.Locale;

/**
 * Business completion policy of a countersign node.
 */
public enum CompletionPolicy {
    ALL,
    ANY,
    PERCENTAGE,
    VETO;

    static CompletionPolicy fromValue(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new FlowableActionException(
                    "Unsupported completion policy: " + value,
                    exception);
        }
    }
}
