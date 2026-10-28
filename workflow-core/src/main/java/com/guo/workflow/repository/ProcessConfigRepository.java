package com.guo.workflow.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ProcessConfigRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProcessConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 根据流程定义 Key 获取业务主键变量名
     * 若未配置，返回 null
     */
    public String findBusinessKeyVar(String processDefinitionKey) {
        String sql = "SELECT business_key_var FROM t_process_config WHERE process_definition_key = ?";

        List<String> results = jdbcTemplate.query(
                sql,
                ps -> {
                    ps.setString(1, processDefinitionKey);
                    ps.setMaxRows(1);
                },
                (rs, rowNum) -> rs.getString("business_key_var")
        );

        return results.isEmpty() ? null : results.get(0);
    }
}
