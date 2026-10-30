package com.guo.workflow.mirror;

import java.time.Instant;

public record ProcessTaskMirror(
        String taskId,
        String processInstanceId,
        String processDefinitionKey,
        String businessKey,
        String tenantId,
        String activityId,
        String nodeName,
        String taskName,
        String taskStatus,
        String taskKind,
        String assigneeId,
        String assigneeName,
        String ownerId,
        String ownerName,
        String candidateUserIds,
        String candidateUserNames,
        String candidateGroupIds,
        String candidateGroupNames,
        String signGroupId,
        Integer signRound,
        Integer signOrder,
        String completionPolicy,
        String vote,
        Instant taskCreateTime,
        Instant claimTime,
        Instant dueTime,
        Instant completeTime,
        Long durationMs,
        Instant lastEventTime) {
}
