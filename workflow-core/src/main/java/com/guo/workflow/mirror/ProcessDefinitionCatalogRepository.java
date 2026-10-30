package com.guo.workflow.mirror;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Business-facing catalog for deployed process definition versions.
 */
@Repository
public class ProcessDefinitionCatalogRepository {

    private static final String UPDATE_SQL = """
            UPDATE t_process_definition_catalog
            SET tenant_id = ?,
                process_key = ?,
                version_number = ?,
                process_name = ?,
                deployment_id = ?,
                source_xml = ?,
                semantic_json = ?,
                status = ?,
                published_time = ?,
                update_time = CURRENT_TIMESTAMP
            WHERE process_definition_id = ?
            """;
    private static final String INSERT_SQL = """
            INSERT INTO t_process_definition_catalog (
                process_definition_id, tenant_id, process_key, version_number,
                process_name, deployment_id, source_xml, semantic_json,
                status, published_time, create_time, update_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                      CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;
    private static final String SELECT_BY_KEY_SQL = """
            SELECT *
            FROM t_process_definition_catalog
            WHERE (tenant_id = ? OR (tenant_id IS NULL AND ? IS NULL))
              AND process_key = ?
            ORDER BY version_number DESC
            """;
    private static final String SELECT_BY_ID_SQL = """
            SELECT *
            FROM t_process_definition_catalog
            WHERE process_definition_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public ProcessDefinitionCatalogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(ProcessDefinitionCatalog definition) {
        int updated = jdbcTemplate.update(
                UPDATE_SQL,
                definition.tenantId(),
                definition.processKey(),
                definition.version(),
                definition.processName(),
                definition.deploymentId(),
                definition.sourceXml(),
                definition.semanticJson(),
                definition.status(),
                timestamp(definition.publishedTime()),
                definition.processDefinitionId());
        if (updated == 0) {
            try {
                jdbcTemplate.update(
                        INSERT_SQL,
                        definition.processDefinitionId(),
                        definition.tenantId(),
                        definition.processKey(),
                        definition.version(),
                        definition.processName(),
                        definition.deploymentId(),
                        definition.sourceXml(),
                        definition.semanticJson(),
                        definition.status(),
                        timestamp(definition.publishedTime()));
            } catch (DuplicateKeyException ignored) {
                upsert(definition);
            }
        }
    }

    public List<ProcessDefinitionCatalog> findByKey(
            String tenantId,
            String processKey) {
        return jdbcTemplate.query(
                SELECT_BY_KEY_SQL,
                (rs, rowNum) -> new ProcessDefinitionCatalog(
                        rs.getString("process_definition_id"),
                        rs.getString("tenant_id"),
                        rs.getString("process_key"),
                        rs.getInt("version_number"),
                        rs.getString("process_name"),
                        rs.getString("deployment_id"),
                        readClob(rs, "source_xml"),
                        readClob(rs, "semantic_json"),
                        rs.getString("status"),
                        instant(rs, "published_time"),
                        instant(rs, "create_time"),
                        instant(rs, "update_time")),
                tenantId,
                tenantId,
                processKey);
    }

    public Optional<ProcessDefinitionCatalog> findByProcessDefinitionId(
            String processDefinitionId) {
        if (processDefinitionId == null || processDefinitionId.isBlank()) {
            return Optional.empty();
        }
        List<ProcessDefinitionCatalog> definitions = jdbcTemplate.query(
                SELECT_BY_ID_SQL,
                (rs, rowNum) -> new ProcessDefinitionCatalog(
                        rs.getString("process_definition_id"),
                        rs.getString("tenant_id"),
                        rs.getString("process_key"),
                        rs.getInt("version_number"),
                        rs.getString("process_name"),
                        rs.getString("deployment_id"),
                        readClob(rs, "source_xml"),
                        readClob(rs, "semantic_json"),
                        rs.getString("status"),
                        instant(rs, "published_time"),
                        instant(rs, "create_time"),
                        instant(rs, "update_time")),
                processDefinitionId);
        return definitions.stream().findFirst();
    }

    private static String readClob(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        if (value != null) {
            return value;
        }
        Clob clob = rs.getClob(column);
        if (clob == null) {
            return null;
        }
        try {
            return clob.getSubString(1, (int) clob.length());
        } finally {
            clob.free();
        }
    }

    private static java.time.Instant instant(ResultSet rs, String column)
            throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(java.time.Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
