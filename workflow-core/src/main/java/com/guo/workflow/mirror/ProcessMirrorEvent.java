package com.guo.workflow.mirror;

import java.time.Instant;

record ProcessMirrorEvent(
        String eventId,
        MirrorEventType eventType,
        String processInstanceId,
        String rootProcessInstanceId,
        String parentProcessInstanceId,
        String processDefinitionId,
        String processDefinitionKey,
        Integer processDefinitionVersion,
        String processName,
        String businessKey,
        String tenantId,
        String processStatus,
        String taskId,
        String activityId,
        String nodeName,
        String taskName,
        String assigneeId,
        String ownerId,
        String candidateUserIds,
        String candidateGroupIds,
        String opinion,
        String startUserId,
        Instant startTime,
        Instant taskCreateTime,
        Instant claimTime,
        Instant dueTime,
        Instant eventTime) {
}
