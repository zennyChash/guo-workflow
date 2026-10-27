package com.guo.workflow.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed representation of one action declaration.
 */
public record ActionDefinition(
        String actionId,
        ActionEvent event,
        ActionType type,
        boolean synchronous,
        String dataSource,
        Map<String, String> attributes,
        List<ActionNode> nodes) {

    public ActionDefinition {
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }
}

