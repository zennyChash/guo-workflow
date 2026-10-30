package com.guo.workflow.mirror;

import java.time.Instant;

public record ProcessActivityLog(
        String eventId,
        String eventScope,
        String eventType,
        String processInstanceId,
        String processDefinitionKey,
        String processName,
        String businessKey,
        String tenantId,
        String taskId,
        String activityId,
        String nodeName,
        String actorId,
        String actorName,
        String fromAssigneeId,
        String fromAssigneeName,
        String toAssigneeId,
        String toAssigneeName,
        String opinion,
        Instant eventTime,
        Instant taskCreateTime,
        Long durationMs,
        String relatedTaskId) {
}
