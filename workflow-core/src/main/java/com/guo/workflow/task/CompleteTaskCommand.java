package com.guo.workflow.task;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public record CompleteTaskCommand(
        String taskId,
        String operatorId,
        String comment,
        Map<String, Object> variables) {

    public CompleteTaskCommand {
        variables = variables == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }
}
