package com.guo.workflow.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Parsed representation of one action declaration.
 */
public record ActionDefinition(
        ActionType type,
        ActionDoType doType,
        boolean synchronous,
        Phase phase,
        String dataSource,
        Map<String, String> attributes,
        List<ActionNode> nodes) {

    public ActionDefinition {
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /**
     * Compatibility accessor derived from the XML attributes.
     */
    public ActionEvent event() {
        return ActionEvent.fromValue(attributes.get("event")).orElse(null);
    }

    /**
     * Compatibility identifier used by instruction dispatch and persistence.
     */
    public String actionId() {
        String configuredId = attributes.get("id");
        if (configuredId == null || configuredId.isBlank()) {
            configuredId = attributes.get("actionId");
        }
        if (configuredId != null && !configuredId.isBlank()) {
            return configuredId;
        }

        String identity = type.value()
                + "|" + doType.value()
                + "|" + attributes
                + "|" + nodes;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }
}

