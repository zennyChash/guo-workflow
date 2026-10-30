package com.guo.workflow.mirror;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC persistence for process, task and activity mirrors.
 */
@Repository
public class ProcessMirrorRepository {

    private static final String UPDATE_INSTANCE_SQL = """
            UPDATE t_process_instance_mirror
            SET root_process_instance_id = ?,
                parent_process_instance_id = ?,
                process_definition_id = ?,
                process_definition_key = ?,
                process_definition_version = ?,
                process_name = ?,
                business_key = ?,
                tenant_id = ?,
                process_status = ?,
                start_user_id = ?,
                start_user_name = ?,
                start_time = ?,
                last_event_time = ?,
                update_time = CURRENT_TIMESTAMP
            WHERE process_instance_id = ?
            """;
    private static final String INSERT_INSTANCE_SQL = """
            INSERT INTO t_process_instance_mirror (
                process_instance_id, root_process_instance_id,
                parent_process_instance_id, process_definition_id,
                process_definition_key, process_definition_version, process_name,
                business_key, tenant_id, process_status, active_task_count,
                current_node_names, current_handler_names, start_user_id,
                start_user_name, start_time, end_time, last_event_time,
                create_time, update_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                      CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;
    private static final String UPDATE_INSTANCE_SUMMARY_SQL = """
            UPDATE t_process_instance_mirror
            SET active_task_count = ?,
                current_node_names = ?,
                current_handler_names = ?,
                last_event_time = ?,
                update_time = CURRENT_TIMESTAMP
            WHERE process_instance_id = ?
            """;
    private static final String COMPLETE_INSTANCE_SQL = """
            UPDATE t_process_instance_mirror
            SET process_status = ?,
                active_task_count = 0,
                current_node_names = NULL,
                current_handler_names = NULL,
                end_time = ?,
                last_event_time = ?,
                update_time = CURRENT_TIMESTAMP
            WHERE process_instance_id = ?
            """;
    private static final String UPDATE_TASK_SQL = """
            UPDATE t_process_task_mirror
            SET process_instance_id = ?,
                process_definition_key = ?,
                business_key = ?,
                tenant_id = ?,
                activity_id = ?,
                node_name = ?,
                task_name = ?,
                task_status = ?,
                task_kind = COALESCE(task_kind, ?),
                assignee_id = ?,
                assignee_name = ?,
                owner_id = ?,
                owner_name = ?,
                candidate_user_ids = ?,
                candidate_user_names = ?,
                candidate_group_ids = ?,
                candidate_group_names = ?,
                sign_group_id = COALESCE(sign_group_id, ?),
                sign_round = COALESCE(sign_round, ?),
                sign_order = COALESCE(sign_order, ?),
                completion_policy = COALESCE(completion_policy, ?),
                vote = COALESCE(vote, ?),
                task_create_time = ?,
                claim_time = ?,
                due_time = ?,
                complete_time = ?,
                duration_ms = ?,
                last_event_time = ?,
                update_time = CURRENT_TIMESTAMP
            WHERE task_id = ?
            """;
    private static final String INSERT_TASK_SQL = """
            INSERT INTO t_process_task_mirror (
                task_id, process_instance_id, process_definition_key,
                business_key, tenant_id, activity_id, node_name, task_name,
                task_status, task_kind, assignee_id, assignee_name,
                owner_id, owner_name, candidate_user_ids, candidate_user_names,
                candidate_group_ids, candidate_group_names, sign_group_id,
                sign_round, sign_order, completion_policy, vote,
                task_create_time, claim_time, due_time, complete_time,
                duration_ms, last_event_time, create_time, update_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                      ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP,
                      CURRENT_TIMESTAMP)
            """;
    private static final String INSERT_ACTIVITY_SQL = """
            INSERT INTO t_process_activity_log (
                event_id, event_scope, event_type, process_instance_id,
                process_definition_key, process_name, business_key, tenant_id,
                task_id, activity_id, node_name, actor_id, actor_name,
                from_assignee_id, from_assignee_name, to_assignee_id,
                to_assignee_name, opinion, event_time, task_create_time,
                duration_ms, related_task_id, create_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                      ?, ?, ?, CURRENT_TIMESTAMP)
            """;
    private static final String SELECT_INSTANCE_SQL = """
            SELECT *
            FROM t_process_instance_mirror
            WHERE (tenant_id = ? OR (tenant_id IS NULL AND ? IS NULL))
              AND process_instance_id = ?
            """;
    private static final String SELECT_BY_BUSINESS_KEY_SQL = """
            SELECT *
            FROM t_process_instance_mirror
            WHERE (tenant_id = ? OR (tenant_id IS NULL AND ? IS NULL))
              AND business_key = ?
            ORDER BY start_time DESC
            """;
    private static final String SELECT_ACTIVE_TASKS_SQL = """
            SELECT *
            FROM t_process_task_mirror
            WHERE (tenant_id = ? OR (tenant_id IS NULL AND ? IS NULL))
              AND process_instance_id = ?
              AND task_status IN ('PENDING', 'CLAIMED')
            ORDER BY task_create_time ASC
            """;
    private static final String SELECT_TASKS_BY_BUSINESS_SQL = """
            SELECT *
            FROM t_process_task_mirror
            WHERE (tenant_id = ? OR (tenant_id IS NULL AND ? IS NULL))
              AND business_key = ?
            ORDER BY task_create_time DESC
            """;
    private static final String SELECT_ACTIVITY_SQL = """
            SELECT *
            FROM t_process_activity_log
            WHERE (tenant_id = ? OR (tenant_id IS NULL AND ? IS NULL))
              AND process_instance_id = ?
            ORDER BY event_time ASC, create_time ASC
            """;
    private static final String SELECT_ACTIVE_TASK_PARTS_SQL = """
            SELECT node_name, assignee_name
            FROM t_process_task_mirror
            WHERE process_instance_id = ?
              AND task_status IN ('PENDING', 'CLAIMED')
            ORDER BY task_create_time ASC
            """;
    private static final String SELECT_TASK_COMPLETED_SQL = """
            SELECT task_status
            FROM t_process_task_mirror
            WHERE task_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public ProcessMirrorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsertInstance(ProcessInstanceMirror instance) {
        int updated = jdbcTemplate.update(
                UPDATE_INSTANCE_SQL,
                instance.rootProcessInstanceId(),
                instance.parentProcessInstanceId(),
                instance.processDefinitionId(),
                instance.processDefinitionKey(),
                instance.processDefinitionVersion(),
                instance.processName(),
                instance.businessKey(),
                instance.tenantId(),
                instance.processStatus(),
                instance.startUserId(),
                instance.startUserName(),
                timestamp(instance.startTime()),
                timestamp(instance.lastEventTime()),
                instance.processInstanceId());
        if (updated == 0) {
            try {
                jdbcTemplate.update(
                        INSERT_INSTANCE_SQL,
                        instance.processInstanceId(),
                        instance.rootProcessInstanceId(),
                        instance.parentProcessInstanceId(),
                        instance.processDefinitionId(),
                        instance.processDefinitionKey(),
                        instance.processDefinitionVersion(),
                        instance.processName(),
                        instance.businessKey(),
                        instance.tenantId(),
                        instance.processStatus(),
                        instance.activeTaskCount(),
                        instance.currentNodeNames(),
                        instance.currentHandlerNames(),
                        instance.startUserId(),
                        instance.startUserName(),
                        timestamp(instance.startTime()),
                        timestamp(instance.endTime()),
                        timestamp(instance.lastEventTime()));
            } catch (DuplicateKeyException ignored) {
                upsertInstance(instance);
            }
        }
    }

    public void upsertTask(ProcessTaskMirror task) {
        int updated = jdbcTemplate.update(
                UPDATE_TASK_SQL,
                task.processInstanceId(),
                task.processDefinitionKey(),
                task.businessKey(),
                task.tenantId(),
                task.activityId(),
                task.nodeName(),
                task.taskName(),
                task.taskStatus(),
                task.taskKind(),
                task.assigneeId(),
                task.assigneeName(),
                task.ownerId(),
                task.ownerName(),
                task.candidateUserIds(),
                task.candidateUserNames(),
                task.candidateGroupIds(),
                task.candidateGroupNames(),
                task.signGroupId(),
                task.signRound(),
                task.signOrder(),
                task.completionPolicy(),
                task.vote(),
                timestamp(task.taskCreateTime()),
                timestamp(task.claimTime()),
                timestamp(task.dueTime()),
                timestamp(task.completeTime()),
                task.durationMs(),
                timestamp(task.lastEventTime()),
                task.taskId());
        if (updated == 0) {
            try {
                jdbcTemplate.update(
                        INSERT_TASK_SQL,
                        task.taskId(),
                        task.processInstanceId(),
                        task.processDefinitionKey(),
                        task.businessKey(),
                        task.tenantId(),
                        task.activityId(),
                        task.nodeName(),
                        task.taskName(),
                        task.taskStatus(),
                        task.taskKind(),
                        task.assigneeId(),
                        task.assigneeName(),
                        task.ownerId(),
                        task.ownerName(),
                        task.candidateUserIds(),
                        task.candidateUserNames(),
                        task.candidateGroupIds(),
                        task.candidateGroupNames(),
                        task.signGroupId(),
                        task.signRound(),
                        task.signOrder(),
                        task.completionPolicy(),
                        task.vote(),
                        timestamp(task.taskCreateTime()),
                        timestamp(task.claimTime()),
                        timestamp(task.dueTime()),
                        timestamp(task.completeTime()),
                        task.durationMs(),
                        timestamp(task.lastEventTime()));
            } catch (DuplicateKeyException ignored) {
                upsertTask(task);
            }
        }
    }

    public void appendActivity(ProcessActivityLog activity) {
        try {
            jdbcTemplate.update(
                    INSERT_ACTIVITY_SQL,
                    activity.eventId(),
                    activity.eventScope(),
                    activity.eventType(),
                    activity.processInstanceId(),
                    activity.processDefinitionKey(),
                    activity.processName(),
                    activity.businessKey(),
                    activity.tenantId(),
                    activity.taskId(),
                    activity.activityId(),
                    activity.nodeName(),
                    activity.actorId(),
                    activity.actorName(),
                    activity.fromAssigneeId(),
                    activity.fromAssigneeName(),
                    activity.toAssigneeId(),
                    activity.toAssigneeName(),
                    activity.opinion(),
                    timestamp(activity.eventTime()),
                    timestamp(activity.taskCreateTime()),
                    activity.durationMs(),
                    activity.relatedTaskId());
        } catch (DuplicateKeyException ignored) {
            // Event delivery is idempotent by event id.
        }
    }

    public void refreshInstanceSummary(String processInstanceId, Instant eventTime) {
        List<ActiveTaskPart> activeTasks = jdbcTemplate.query(
                SELECT_ACTIVE_TASK_PARTS_SQL,
                (rs, rowNum) -> new ActiveTaskPart(
                        rs.getString("node_name"),
                        rs.getString("assignee_name")),
                processInstanceId);
        String nodes = joinDistinct(activeTasks.stream().map(ActiveTaskPart::nodeName).toList());
        String handlers = joinDistinct(activeTasks.stream().map(ActiveTaskPart::assigneeName).toList());
        jdbcTemplate.update(
                UPDATE_INSTANCE_SUMMARY_SQL,
                activeTasks.size(),
                nodes,
                handlers,
                timestamp(eventTime),
                processInstanceId);
    }

    public boolean isTaskCompleted(String taskId) {
        List<String> statuses = jdbcTemplate.query(
                SELECT_TASK_COMPLETED_SQL,
                (rs, rowNum) -> rs.getString("task_status"),
                taskId);
        return !statuses.isEmpty() && "COMPLETED".equals(statuses.get(0));
    }

    public void completeInstance(
            String processInstanceId,
            String processStatus,
            Instant endTime) {
        jdbcTemplate.update(
                COMPLETE_INSTANCE_SQL,
                processStatus,
                timestamp(endTime),
                timestamp(endTime),
                processInstanceId);
    }

    public Optional<ProcessInstanceMirror> findInstance(
            String tenantId,
            String processInstanceId) {
        return jdbcTemplate.query(
                        SELECT_INSTANCE_SQL,
                        INSTANCE_ROW_MAPPER,
                        tenantId,
                tenantId,
                        processInstanceId)
                .stream()
                .findFirst();
    }

    public List<ProcessInstanceMirror> findInstancesByBusinessKey(
            String tenantId,
            String businessKey) {
        return jdbcTemplate.query(
                SELECT_BY_BUSINESS_KEY_SQL,
                INSTANCE_ROW_MAPPER,
                tenantId,
                tenantId,
                businessKey);
    }

    public List<ProcessTaskMirror> findActiveTasks(
            String tenantId,
            String processInstanceId) {
        return jdbcTemplate.query(
                SELECT_ACTIVE_TASKS_SQL,
                TASK_ROW_MAPPER,
                tenantId,
                tenantId,
                processInstanceId);
    }

    public List<ProcessTaskMirror> findTasksByBusinessKey(
            String tenantId,
            String businessKey) {
        return jdbcTemplate.query(
                SELECT_TASKS_BY_BUSINESS_SQL,
                TASK_ROW_MAPPER,
                tenantId,
                tenantId,
                businessKey);
    }

    public List<ProcessActivityLog> findActivity(
            String tenantId,
            String processInstanceId) {
        return jdbcTemplate.query(
                SELECT_ACTIVITY_SQL,
                ACTIVITY_ROW_MAPPER,
                tenantId,
                tenantId,
                processInstanceId);
    }

    private static final RowMapper<ProcessInstanceMirror> INSTANCE_ROW_MAPPER =
            (rs, rowNum) -> new ProcessInstanceMirror(
                    rs.getString("process_instance_id"),
                    rs.getString("root_process_instance_id"),
                    rs.getString("parent_process_instance_id"),
                    rs.getString("process_definition_id"),
                    rs.getString("process_definition_key"),
                    nullableInt(rs, "process_definition_version"),
                    rs.getString("process_name"),
                    rs.getString("business_key"),
                    rs.getString("tenant_id"),
                    rs.getString("process_status"),
                    rs.getInt("active_task_count"),
                    rs.getString("current_node_names"),
                    rs.getString("current_handler_names"),
                    rs.getString("start_user_id"),
                    rs.getString("start_user_name"),
                    instant(rs, "start_time"),
                    instant(rs, "end_time"),
                    instant(rs, "last_event_time"));

    private static final RowMapper<ProcessTaskMirror> TASK_ROW_MAPPER =
            (rs, rowNum) -> new ProcessTaskMirror(
                    rs.getString("task_id"),
                    rs.getString("process_instance_id"),
                    rs.getString("process_definition_key"),
                    rs.getString("business_key"),
                    rs.getString("tenant_id"),
                    rs.getString("activity_id"),
                    rs.getString("node_name"),
                    rs.getString("task_name"),
                    rs.getString("task_status"),
                    rs.getString("task_kind"),
                    rs.getString("assignee_id"),
                    rs.getString("assignee_name"),
                    rs.getString("owner_id"),
                    rs.getString("owner_name"),
                    rs.getString("candidate_user_ids"),
                    rs.getString("candidate_user_names"),
                    rs.getString("candidate_group_ids"),
                    rs.getString("candidate_group_names"),
                    rs.getString("sign_group_id"),
                    nullableInt(rs, "sign_round"),
                    nullableInt(rs, "sign_order"),
                    rs.getString("completion_policy"),
                    rs.getString("vote"),
                    instant(rs, "task_create_time"),
                    instant(rs, "claim_time"),
                    instant(rs, "due_time"),
                    instant(rs, "complete_time"),
                    nullableLong(rs, "duration_ms"),
                    instant(rs, "last_event_time"));

    private static final RowMapper<ProcessActivityLog> ACTIVITY_ROW_MAPPER =
            (rs, rowNum) -> new ProcessActivityLog(
                    rs.getString("event_id"),
                    rs.getString("event_scope"),
                    rs.getString("event_type"),
                    rs.getString("process_instance_id"),
                    rs.getString("process_definition_key"),
                    rs.getString("process_name"),
                    rs.getString("business_key"),
                    rs.getString("tenant_id"),
                    rs.getString("task_id"),
                    rs.getString("activity_id"),
                    rs.getString("node_name"),
                    rs.getString("actor_id"),
                    rs.getString("actor_name"),
                    rs.getString("from_assignee_id"),
                    rs.getString("from_assignee_name"),
                    rs.getString("to_assignee_id"),
                    rs.getString("to_assignee_name"),
                    rs.getString("opinion"),
                    instant(rs, "event_time"),
                    instant(rs, "task_create_time"),
                    nullableLong(rs, "duration_ms"),
                    rs.getString("related_task_id"));

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String joinDistinct(List<String> values) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .reduce((left, right) -> left + "," + right)
                .orElse(null);
    }

    private record ActiveTaskPart(String nodeName, String assigneeName) {
    }
}
