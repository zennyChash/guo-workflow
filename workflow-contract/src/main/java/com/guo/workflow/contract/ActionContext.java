package com.guo.workflow.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Framework-neutral process context passed to business actions.
 */
public record ActionContext(
        String businessKey,
        String processDefinitionKey,
        String processInstanceId,
        String taskId,
        String activityId,
        Map<String, Object> variables,
        Map<String, Object> attributes) {

    public ActionContext {
        variables = immutableCopy(variables);
        attributes = immutableCopy(attributes);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}

