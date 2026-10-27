package com.guo.workflow.contract;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Message produced for asynchronous consumers.
 */
public record InstructionMsg(
        String instructionId,
        String businessKey,
        String target,
        String templateId,
        String template,
        Map<String, Object> payload,
        Instant createdAt) {

    public InstructionMsg {
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}

