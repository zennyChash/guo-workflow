package com.guo.workflow.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic node in the action XML tree. Type-specific interpretation belongs to executors.
 */
public record ActionNode(
        String name,
        String text,
        Map<String, String> attributes,
        List<ActionNode> children) {

    public ActionNode {
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        children = children == null
                ? List.of()
                : List.copyOf(children);
    }
}

