package com.guo.workflow.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guo.workflow.contract.ActionContext;
import com.guo.workflow.contract.BusinessAction;
import com.guo.workflow.contract.InstructionMsg;
import com.guo.workflow.dispatcher.BusinessActionRegistry;
import org.flowable.engine.RuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Claims persisted tasks atomically and executes them on a bounded worker pool.
 */
@Component
public class AsyncTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskConsumer.class);
    private static final int MAX_ERROR_LENGTH = 2000;
    private static final String SELECT_PENDING_SQL = """
            SELECT task_id
            FROM t_async_task
            WHERE status IN ('PENDING', 'RETRY_WAIT')
              AND (next_retry_time IS NULL OR next_retry_time <= CURRENT_TIMESTAMP)
            ORDER BY create_time ASC
            """;
    private static final String RECOVER_STALE_SQL = """
            UPDATE t_async_task
            SET status = 'PENDING',
                claim_token = NULL,
                update_time = CURRENT_TIMESTAMP
            WHERE status = 'PROCESSING'
              AND update_time < ?
            """;
    private static final String CLAIM_TASK_SQL = """
            UPDATE t_async_task
            SET status = 'PROCESSING',
                claim_token = ?,
                next_retry_time = NULL,
                update_time = CURRENT_TIMESTAMP
            WHERE task_id = ?
              AND status IN ('PENDING', 'RETRY_WAIT')
              AND (next_retry_time IS NULL OR next_retry_time <= CURRENT_TIMESTAMP)
            """;
    private static final String RELEASE_CLAIM_SQL = """
            UPDATE t_async_task
            SET status = 'PENDING',
                claim_token = NULL,
                update_time = CURRENT_TIMESTAMP
            WHERE task_id = ?
              AND status = 'PROCESSING'
              AND claim_token = ?
            """;
    private static final String SELECT_CLAIMED_TASK_SQL = """
            SELECT task_id, payload_json, retry_count, max_retry_count
            FROM t_async_task
            WHERE task_id = ?
              AND status = 'PROCESSING'
              AND claim_token = ?
            """;
    private static final String MARK_SUCCESS_SQL = """
            UPDATE t_async_task
            SET status = 'SUCCESS',
                claim_token = NULL,
                next_retry_time = NULL,
                error_msg = NULL,
                result_json = ?,
                update_time = CURRENT_TIMESTAMP
            WHERE task_id = ?
              AND status = 'PROCESSING'
              AND claim_token = ?
            """;
    private static final String MARK_RETRY_SQL = """
            UPDATE t_async_task
            SET status = 'RETRY_WAIT',
                retry_count = retry_count + 1,
                next_retry_time = ?,
                claim_token = NULL,
                error_msg = ?,
                update_time = CURRENT_TIMESTAMP
            WHERE task_id = ?
              AND status = 'PROCESSING'
              AND claim_token = ?
            """;
    private static final String MARK_DEAD_SQL = """
            UPDATE t_async_task
            SET status = 'DEAD',
                next_retry_time = NULL,
                claim_token = NULL,
                error_msg = ?,
                update_time = CURRENT_TIMESTAMP
            WHERE task_id = ?
              AND status = 'PROCESSING'
              AND claim_token = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final BusinessActionRegistry registry;
    private final TaskExecutor asyncTaskExecutor;
    private final int fetchSize;
    private final long retryBaseSeconds;
    private final long retryMaxDelaySeconds;
    private final double jitterRatio;
    private final long processingTimeoutSeconds;
    private RuntimeService runtimeService;

    public AsyncTaskConsumer(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            BusinessActionRegistry registry,
            @Qualifier("asyncTaskExecutor") TaskExecutor asyncTaskExecutor,
            @Value("${guo.workflow.fetch-size:10}") int fetchSize,
            @Value("${guo.workflow.async.retry-base-seconds:2}") long retryBaseSeconds,
            @Value("${guo.workflow.async.retry-max-delay-seconds:300}") long retryMaxDelaySeconds,
            @Value("${guo.workflow.async.jitter-ratio:0.2}") double jitterRatio,
            @Value("${guo.workflow.async.processing-timeout-seconds:600}")
            long processingTimeoutSeconds) {
        if (fetchSize < 1) {
            throw new IllegalArgumentException("guo.workflow.fetch-size must be greater than zero");
        }
        if (retryBaseSeconds < 1 || retryMaxDelaySeconds < retryBaseSeconds) {
            throw new IllegalArgumentException("Invalid async task retry delay configuration");
        }
        if (jitterRatio < 0 || jitterRatio > 1) {
            throw new IllegalArgumentException("jitter-ratio must be between 0 and 1");
        }
        if (processingTimeoutSeconds < 1) {
            throw new IllegalArgumentException("processing-timeout-seconds must be positive");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.asyncTaskExecutor = asyncTaskExecutor;
        this.fetchSize = fetchSize;
        this.retryBaseSeconds = retryBaseSeconds;
        this.retryMaxDelaySeconds = retryMaxDelaySeconds;
        this.jitterRatio = jitterRatio;
        this.processingTimeoutSeconds = processingTimeoutSeconds;
    }

    @Autowired(required = false)
    public void setRuntimeService(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @Scheduled(fixedDelay = 5000)
    public void consumePendingTasks() {
        recoverStaleTasks();

        List<String> taskIds;
        try {
            taskIds = fetchPendingTaskIds();
        } catch (RuntimeException exception) {
            log.error("Failed to fetch pending async tasks", exception);
            return;
        }

        for (String taskId : taskIds) {
            String claimToken = UUID.randomUUID().toString();
            if (!claimTask(taskId, claimToken)) {
                continue;
            }
            try {
                asyncTaskExecutor.execute(() -> executeClaimedTask(taskId, claimToken));
            } catch (TaskRejectedException exception) {
                log.warn("Async task queue is full; releasing taskId={}", taskId);
                releaseClaim(taskId, claimToken);
            } catch (RuntimeException exception) {
                log.error("Failed to submit async task taskId={}", taskId, exception);
                releaseClaim(taskId, claimToken);
            }
        }
    }

    private void recoverStaleTasks() {
        Timestamp cutoff = Timestamp.from(
                Instant.now().minusSeconds(processingTimeoutSeconds));
        try {
            int recovered = jdbcTemplate.update(RECOVER_STALE_SQL, cutoff);
            if (recovered > 0) {
                log.warn("Recovered {} stale PROCESSING async task(s)", recovered);
            }
        } catch (RuntimeException exception) {
            log.error("Failed to recover stale async tasks", exception);
        }
    }

    private List<String> fetchPendingTaskIds() {
        return jdbcTemplate.query(
                SELECT_PENDING_SQL,
                statement -> {
                    statement.setMaxRows(fetchSize);
                    statement.setFetchSize(fetchSize);
                },
                (resultSet, rowNumber) -> resultSet.getString("task_id"));
    }

    private boolean claimTask(String taskId, String claimToken) {
        try {
            return jdbcTemplate.update(CLAIM_TASK_SQL, claimToken, taskId) == 1;
        } catch (RuntimeException exception) {
            log.error("Failed to claim async task taskId={}", taskId, exception);
            return false;
        }
    }

    private void releaseClaim(String taskId, String claimToken) {
        try {
            jdbcTemplate.update(RELEASE_CLAIM_SQL, taskId, claimToken);
        } catch (RuntimeException exception) {
            log.error("Failed to release async task claim taskId={}", taskId, exception);
        }
    }

    private void executeClaimedTask(String taskId, String claimToken) {
        Optional<LoadedTask> loadedTask;
        try {
            loadedTask = loadClaimedTask(taskId, claimToken);
        } catch (RuntimeException exception) {
            log.error("Failed to load claimed async task taskId={}", taskId, exception);
            return;
        }
        if (loadedTask.isEmpty()) {
            log.warn("Async task claim is no longer valid taskId={}", taskId);
            return;
        }

        LoadedTask task = loadedTask.get();
        if (task.payloadFailure() != null) {
            handleFailure(taskId, claimToken, task, task.payloadFailure());
            return;
        }

        ActionContext context = task.message().invocation().context();
        Map<String, Object> variablesBefore =
                new LinkedHashMap<>(context.variables());
        try {
            BusinessAction action = registry.resolve(
                    task.message().type(),
                    task.message().definition().doType());
            action.execute(task.message().invocation(), task.message().definition());
        } catch (Exception exception) {
            log.error("Async task failed taskId={}", taskId, exception);
            handleFailure(taskId, claimToken, task, exception);
            return;
        }

        Map<String, Object> producedVariables =
                changedVariables(variablesBefore, context.variables());
        String resultJson;
        try {
            resultJson = producedVariables.isEmpty()
                    ? null
                    : objectMapper.writeValueAsString(producedVariables);
        } catch (Exception exception) {
            log.error("Failed to serialize async action result taskId={}", taskId, exception);
            handleFailure(taskId, claimToken, task, exception);
            return;
        }

        boolean markedSuccessful = markSuccess(
                taskId,
                claimToken,
                task.message(),
                resultJson);
        if (markedSuccessful) {
            writeProcessVariables(context, producedVariables);
        }
    }

    private Optional<LoadedTask> loadClaimedTask(String taskId, String claimToken) {
        List<LoadedTask> tasks = jdbcTemplate.query(
                SELECT_CLAIMED_TASK_SQL,
                statement -> {
                    statement.setString(1, taskId);
                    statement.setString(2, claimToken);
                    statement.setMaxRows(1);
                },
                this::mapClaimedTask);
        return tasks.stream().findFirst();
    }

    private LoadedTask mapClaimedTask(ResultSet resultSet, int rowNumber) throws SQLException {
        String taskId = resultSet.getString("task_id");
        try {
            return new LoadedTask(
                    taskId,
                    readPayloadJson(resultSet),
                    resultSet.getInt("retry_count"),
                    resultSet.getInt("max_retry_count"),
                    null);
        } catch (SQLException | RuntimeException exception) {
            return new LoadedTask(
                    taskId,
                    null,
                    resultSet.getInt("retry_count"),
                    resultSet.getInt("max_retry_count"),
                    exception);
        }
    }

    private boolean markSuccess(
            String taskId,
            String claimToken,
            InstructionMsg message,
            String resultJson) {
        try {
            int updated = jdbcTemplate.update(
                    MARK_SUCCESS_SQL,
                    resultJson,
                    taskId,
                    claimToken);
            if (updated == 1) {
                log.info(
                        "Async task succeeded taskId={}, type={}, businessKey={}",
                        taskId,
                        message.type(),
                        message.businessKey());
            } else {
                log.warn("Stale async task completion ignored taskId={}", taskId);
            }
            return updated == 1;
        } catch (RuntimeException exception) {
            log.error("Failed to mark async task as SUCCESS taskId={}", taskId, exception);
            return false;
        }
    }

    private void writeProcessVariables(
            ActionContext context,
            Map<String, Object> producedVariables) {
        if (runtimeService == null
                || producedVariables.isEmpty()
                || context.processInstanceId() == null
                || context.processInstanceId().isBlank()) {
            return;
        }
        try {
            long activeProcesses = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(context.processInstanceId())
                    .count();
            if (activeProcesses == 0) {
                log.info(
                        "Process instance is no longer active; result remains in async task record processInstanceId={}",
                        context.processInstanceId());
                return;
            }
            runtimeService.setVariables(
                    context.processInstanceId(),
                    producedVariables);
            log.info(
                    "Persisted async action result variables processInstanceId={}, variableCount={}",
                    context.processInstanceId(),
                    producedVariables.size());
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to persist async action result variables processInstanceId={}",
                    context.processInstanceId(),
                    exception);
        }
    }

    private Map<String, Object> changedVariables(
            Map<String, Object> before,
            Map<String, Object> after) {
        Map<String, Object> changed = new LinkedHashMap<>();
        after.forEach((name, value) -> {
            if (!before.containsKey(name) || !Objects.equals(before.get(name), value)) {
                changed.put(name, value);
            }
        });
        return changed;
    }

    private void handleFailure(
            String taskId,
            String claimToken,
            LoadedTask task,
            Exception failure) {
        String errorMessage = stackTrace(failure);
        try {
            if (task.retryCount() >= task.maxRetryCount()) {
                markDead(taskId, claimToken, errorMessage);
                return;
            }

            long delaySeconds = retryDelaySeconds(task.retryCount());
            Timestamp nextRetryTime = Timestamp.from(
                    Instant.now().plusSeconds(delaySeconds));
            int updated = jdbcTemplate.update(
                    MARK_RETRY_SQL,
                    nextRetryTime,
                    errorMessage,
                    taskId,
                    claimToken);
            if (updated == 1) {
                log.warn(
                        "Async task scheduled for retry taskId={}, retryCount={}, delaySeconds={}",
                        taskId,
                        task.retryCount() + 1,
                        delaySeconds);
            } else {
                log.warn("Stale async task failure ignored taskId={}", taskId);
            }
        } catch (RuntimeException exception) {
            log.error("Failed to record async task failure taskId={}", taskId, exception);
        }
    }

    private void markDead(String taskId, String claimToken, String errorMessage) {
        int updated;
        try {
            updated = jdbcTemplate.update(
                    MARK_DEAD_SQL,
                    errorMessage,
                    taskId,
                    claimToken);
        } catch (RuntimeException exception) {
            log.error("Failed to mark async task as DEAD taskId={}", taskId, exception);
            return;
        }
        if (updated == 1) {
            log.error("Async task moved to DEAD taskId={}", taskId);
        } else {
            log.warn("Stale async task dead-letter update ignored taskId={}", taskId);
        }
    }

    private long retryDelaySeconds(int retryCount) {
        long exponentialDelay = retryBaseSeconds * (1L << Math.min(retryCount, 20));
        long cappedDelay = Math.min(exponentialDelay, retryMaxDelaySeconds);
        if (jitterRatio == 0) {
            return Math.max(1L, cappedDelay);
        }
        double jitter = 1.0d + ThreadLocalRandom.current().nextDouble(
                -jitterRatio,
                jitterRatio);
        return Math.min(
                retryMaxDelaySeconds,
                Math.max(1L, Math.round(cappedDelay * jitter)));
    }

    InstructionMsg readPayloadJson(ResultSet resultSet) throws SQLException {
        String payloadJson;
        try {
            payloadJson = resultSet.getString("payload_json");
            if (payloadJson == null) {
                throw new SQLException("CLOB requires explicit access");
            }
        } catch (SQLException | RuntimeException exception) {
            log.debug("Reading payload_json as String failed; falling back to Clob", exception);
            Clob clob = resultSet.getClob("payload_json");
            if (clob == null) {
                throw new SQLException("Async task payload_json must not be null");
            }
            try {
                payloadJson = clob.getSubString(1, (int) clob.length());
            } finally {
                try {
                    clob.free();
                } catch (SQLException freeException) {
                    log.warn("Failed to release payload_json Clob", freeException);
                }
            }
        }

        try {
            return objectMapper.readValue(payloadJson, InstructionMsg.class);
        } catch (Exception exception) {
            throw new SQLException("Failed to deserialize async task payload_json", exception);
        }
    }

    private String stackTrace(Exception exception) {
        StringWriter writer = new StringWriter();
        exception.printStackTrace(new PrintWriter(writer));
        String stackTrace = writer.toString();
        return stackTrace.substring(0, Math.min(stackTrace.length(), MAX_ERROR_LENGTH));
    }

    private record LoadedTask(
            String taskId,
            InstructionMsg message,
            int retryCount,
            int maxRetryCount,
            Exception payloadFailure) {
    }
}
