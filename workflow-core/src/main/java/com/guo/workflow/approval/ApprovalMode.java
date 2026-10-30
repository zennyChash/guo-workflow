package com.guo.workflow.approval;

import com.guo.workflow.contract.exception.FlowableActionException;

import java.util.Locale;

/**
 * Supported approval node semantics.
 */
public enum ApprovalMode {
    SINGLE,
    COUNTERSIGN,
    OR_SIGN;

    static ApprovalMode fromValue(String value) {
        if (value == null || value.isBlank()) {
            return SINGLE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new FlowableActionException(
                    "Unsupported approval mode: " + value,
                    exception);
        }
    }
}
