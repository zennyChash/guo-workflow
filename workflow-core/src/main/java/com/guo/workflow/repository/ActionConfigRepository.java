package com.guo.workflow.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class ActionConfigRepository {

    private static final String SELECT_SQL = """
            SELECT id, process_definition_key, activity_id, event, action_xml, version, enabled, remark
            FROM t_node_action_config
            WHERE process_definition_key = ?
              AND activity_id = ?
              AND event = ?
              AND enabled = 1
            """;

    private final JdbcTemplate jdbcTemplate;

    public ActionConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 根据流程定义、节点和事件查询动作配置
     */
    public Optional<ActionConfig> findByNodeAndEvent(String processDefinitionKey,
                                                     String activityId,
                                                     String event) {
        List<ActionConfig> list = jdbcTemplate.query(
                SELECT_SQL,
                ps -> {
                    ps.setString(1, processDefinitionKey);
                    ps.setString(2, activityId);
                    ps.setString(3, event);
                    // 方言兼容：不写 LIMIT 1，利用 JDBC 原生限制结果集
                    ps.setMaxRows(1);
                    ps.setFetchSize(1);
                },
                CONFIG_ROW_MAPPER
        );
        return list.stream().findFirst();
    }

    private static final RowMapper<ActionConfig> CONFIG_ROW_MAPPER = (rs, rowNum) -> {
        try {
            return new ActionConfig(
                    rs.getLong("id"),
                    rs.getString("process_definition_key"),
                    rs.getString("activity_id"),
                    rs.getString("event"),
                    readClobAsString(rs, "action_xml"),
                    rs.getInt("version"),
                    rs.getInt("enabled") == 1,
                    rs.getString("remark")
            );
        } catch (SQLException e) {
            throw new RuntimeException("映射流程动作配置失败", e);
        }
    };

    /**
     * 处理 Oracle/MySQL CLOB 读取兼容
     */
    private static String readClobAsString(ResultSet rs, String columnName) throws SQLException {
        String value = rs.getString(columnName);
        if (value != null) {
            return value;
        }
        Clob clob = rs.getClob(columnName);
        if (clob == null) {
            return null;
        }
        try {
            return clob.getSubString(1, (int) clob.length());
        } finally {
            try {
                clob.free();
            } catch (SQLException freeEx) {
            }
        }
    }

    /**
     * 配置实体（record）
     */
    public record ActionConfig(
            Long id,
            String processDefinitionKey,
            String activityId,
            String event,
            String actionXml,
            int version,
            boolean enabled,
            String remark
    ) {
    }
}
