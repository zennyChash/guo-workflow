package com.guo.workflow.business;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stable envelope passed from workflow actions to host business operations.
 */
public record BusinessOperationRequest(
        String uniqueOperationKey,
        String actionKey,
        int version,
        String tenantId,
        String processDefinitionKey,
        String businessKey,
        String processInstanceId,
        String workflowTaskId,
        String activityId,
        String actionId,
        String event,
        Map<String, Object> parameters) {

    public BusinessOperationRequest {
        parameters = parameters == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }
}
