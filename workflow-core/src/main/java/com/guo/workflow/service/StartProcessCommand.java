package com.guo.workflow.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Starts one process with an explicit tenant, operator and business form data.
 */
public record StartProcessCommand(
        String processKey,
        String operatorId,
        String tenantId,
        Map<String, Object> variables) {

    public StartProcessCommand {
        variables = variables == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }
}
