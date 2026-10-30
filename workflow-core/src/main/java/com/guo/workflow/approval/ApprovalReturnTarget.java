package com.guo.workflow.approval;

/**
 * One explicitly allowed target for returning an approval task.
 */
public record ApprovalReturnTarget(
        String activityId,
        String name) {
}
