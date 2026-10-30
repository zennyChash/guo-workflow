package com.guo.workflow.approval;

import java.math.BigDecimal;
import java.util.List;

/**
 * Extracted semantic description of one guo approval node.
 */
public record ApprovalNodeSpec(
        String activityId,
        String nodeName,
        ApprovalMode mode,
        String approvers,
        boolean sequential,
        CompletionPolicy completionPolicy,
        BigDecimal threshold,
        boolean commentRequired,
        String rejectPolicy,
        List<ApprovalReturnTarget> allowedReturnTargets,
        List<NotificationSpec> notifications) {

    public ApprovalNodeSpec {
        allowedReturnTargets = allowedReturnTargets == null
                ? List.of()
                : List.copyOf(allowedReturnTargets);
        notifications = notifications == null ? List.of() : List.copyOf(notifications);
    }
}
