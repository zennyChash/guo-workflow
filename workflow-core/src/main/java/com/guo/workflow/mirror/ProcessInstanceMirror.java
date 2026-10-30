package com.guo.workflow.mirror;

import java.time.Instant;

public record ProcessInstanceMirror(
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
        int activeTaskCount,
        String currentNodeNames,
        String currentHandlerNames,
        String startUserId,
        String startUserName,
        Instant startTime,
        Instant endTime,
        Instant lastEventTime) {
}
