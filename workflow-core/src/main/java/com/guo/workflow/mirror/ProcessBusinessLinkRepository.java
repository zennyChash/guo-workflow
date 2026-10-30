package com.guo.workflow.mirror;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * JDBC persistence for process-to-business document relations.
 */
@Repository
public class ProcessBusinessLinkRepository {

    private static final String UPDATE_SQL = """
            UPDATE t_process_business_link
            SET tenant_id = ?,
                system_code = ?,
                business_type = ?,
                business_id = ?,
                business_key = ?,
                business_title = ?,
                relation_type = ?,
                business_sync_status = ?,
                business_url = ?,
                update_time = CURRENT_TIMESTAMP
            WHERE link_id = ?
            """;
    private static final String INSERT_SQL = """
            INSERT INTO t_process_business_link (
                link_id, process_instance_id, tenant_id, system_code,
                business_type, business_id, business_key, business_title,
                relation_type, business_sync_status, business_url,
                create_time, update_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                      CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;
    private static final String SELECT_BY_PROCESS_SQL = """
            SELECT *
            FROM t_process_business_link
            WHERE (tenant_id = ? OR (tenant_id IS NULL AND ? IS NULL))
              AND process_instance_id = ?
            ORDER BY create_time ASC
            """;
    private static final String SELECT_BY_BUSINESS_SQL = """
            SELECT *
            FROM t_process_business_link
            WHERE (tenant_id = ? OR (tenant_id IS NULL AND ? IS NULL))
              AND system_code = ?
              AND business_type = ?
              AND business_id = ?
            ORDER BY create_time DESC
            """;

    private final JdbcTemplate jdbcTemplate;

    public ProcessBusinessLinkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(ProcessBusinessLink link) {
        int updated = jdbcTemplate.update(
                UPDATE_SQL,
                link.tenantId(),
                link.systemCode(),
                link.businessType(),
                link.businessId(),
                link.businessKey(),
                link.businessTitle(),
                link.relationType(),
                link.businessSyncStatus(),
                link.businessUrl(),
                link.linkId());
        if (updated == 0) {
            try {
                jdbcTemplate.update(
                        INSERT_SQL,
                        link.linkId(),
                        link.processInstanceId(),
                        link.tenantId(),
                        link.systemCode(),
                        link.businessType(),
                        link.businessId(),
                        link.businessKey(),
                        link.businessTitle(),
                        link.relationType(),
                        link.businessSyncStatus(),
                        link.businessUrl());
            } catch (DuplicateKeyException ignored) {
                upsert(link);
            }
        }
    }

    public List<ProcessBusinessLink> findByProcess(
            String tenantId,
            String processInstanceId) {
        return jdbcTemplate.query(
                SELECT_BY_PROCESS_SQL,
                (rs, rowNum) -> new ProcessBusinessLink(
                        rs.getString("link_id"),
                        rs.getString("process_instance_id"),
                        rs.getString("tenant_id"),
                        rs.getString("system_code"),
                        rs.getString("business_type"),
                        rs.getString("business_id"),
                        rs.getString("business_key"),
                        rs.getString("business_title"),
                        rs.getString("relation_type"),
                        rs.getString("business_sync_status"),
                        rs.getString("business_url"),
                        toInstant(rs.getTimestamp("create_time")),
                        toInstant(rs.getTimestamp("update_time"))),
                tenantId,
                tenantId,
                processInstanceId);
    }

    public List<ProcessBusinessLink> findByBusiness(
            String tenantId,
            String systemCode,
            String businessType,
            String businessId) {
        return jdbcTemplate.query(
                SELECT_BY_BUSINESS_SQL,
                (rs, rowNum) -> new ProcessBusinessLink(
                        rs.getString("link_id"),
                        rs.getString("process_instance_id"),
                        rs.getString("tenant_id"),
                        rs.getString("system_code"),
                        rs.getString("business_type"),
                        rs.getString("business_id"),
                        rs.getString("business_key"),
                        rs.getString("business_title"),
                        rs.getString("relation_type"),
                        rs.getString("business_sync_status"),
                        rs.getString("business_url"),
                        toInstant(rs.getTimestamp("create_time")),
                        toInstant(rs.getTimestamp("update_time"))),
                tenantId,
                tenantId,
                systemCode,
                businessType,
                businessId);
    }

    private static java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
