package com.guo.workflow.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guo.workflow.contract.InstructionMsg;
import com.guo.workflow.contract.exception.FlowableActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Persists asynchronous instructions for consumption by a worker process.
 */
@Component
public class JdbcInstructionPublisher implements InstructionPublisher {

    private static final Logger log = LoggerFactory.getLogger(JdbcInstructionPublisher.class);
    private static final String INSERT_SQL = """
            INSERT INTO t_async_task
                (task_id, business_key, tenant_id, action_type, payload_json, status, create_time)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcInstructionPublisher(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(InstructionMsg message) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new FlowableActionException(
                    "Failed to serialize instruction: " + message.taskId(),
                    exception);
        }

        try {
            jdbcTemplate.update(
                    INSERT_SQL,
                    message.taskId(),
                    message.businessKey(),
                    tenantId(message),
                    message.type().name(),
                    payloadJson,
                    "PENDING",
                    Timestamp.from(Instant.now()));
            log.info(
                    "Persisted async task taskId={}, type={}, businessKey={}",
                    message.taskId(),
                    message.type(),
                    message.businessKey());
        } catch (RuntimeException exception) {
            log.error(
                    "Failed to persist async task taskId={}, type={}, businessKey={}",
                    message.taskId(),
                    message.type(),
                    message.businessKey(),
                    exception);
            throw new FlowableActionException(
                    "Failed to persist instruction: " + message.taskId(),
                    exception);
        }
    }

    private String tenantId(InstructionMsg message) {
        Object tenantId = message.invocation().context().attributes().get("tenantId");
        if (tenantId == null) {
            tenantId = message.invocation().context().attributes().get("tenant_id");
        }
        if (tenantId == null) {
            tenantId = message.invocation().context().variables().get("tenantId");
        }
        return tenantId == null || tenantId.toString().isBlank()
                ? "default"
                : tenantId.toString();
    }
}
