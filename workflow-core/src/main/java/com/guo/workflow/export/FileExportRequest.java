package com.guo.workflow.export;

import com.guo.workflow.contract.ActionContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Export request sent across the boundary to the export module.
 *
 * <p>The engine passes the configured task definition identifier and file type
 * without interpreting them.</p>
 */
public record FileExportRequest(
        String uniqueBizKey,
        String expTaskId,
        String fileType,
        String actionId,
        String processDefinitionKey,
        String activityId,
        String businessKey,
        String processInstanceId,
        String workflowTaskId,
        String tenantId,
        String event,
        String resultVariableName,
        Map<String, String> targetAttributes,
        ActionContext context) {

    public FileExportRequest {
        targetAttributes = targetAttributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(targetAttributes));
    }
}
