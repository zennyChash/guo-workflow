package com.guo.workflow.task;

import java.time.Instant;

/**
 * Business-facing view of an active workflow task.
 */
public record TaskView(
        String taskId,
        String processInstanceId,
        String processDefinitionId,
        String processDefinitionKey,
        String businessKey,
        String tenantId,
        String activityId,
        String taskName,
        String assigneeId,
        String ownerId,
        String delegationState,
        Instant createTime,
        Instant dueTime) {
}
