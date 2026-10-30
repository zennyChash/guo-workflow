package com.guo.workflow.repository;

import com.guo.workflow.contract.ActionTriggerScope;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionConfigRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private ActionConfigRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:action_config_"
                        + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE t_node_action_config (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    process_definition_key VARCHAR(64) NOT NULL,
                    process_definition_id VARCHAR(128),
                    activity_id VARCHAR(64) NOT NULL,
                    trigger_scope VARCHAR(16) NOT NULL,
                    event VARCHAR(32) NOT NULL,
                    action_xml CLOB NOT NULL,
                    version INT NOT NULL,
                    enabled INT NOT NULL,
                    remark VARCHAR(255)
                )
                """);
        repository = new ActionConfigRepository(jdbcTemplate);
    }

    @Test
    void resolvesByDefinitionVersionBeforeLegacyConfiguration() {
        insert("order", null, "approve", "TASK", "complete", "legacy");
        insert("order", "order:1:123", "approve", "TASK", "complete", "versioned");

        ActionConfigRepository.ActionConfig config = repository
                .findByDefinitionAndScope(
                        "order:1:123",
                        "order",
                        "approve",
                        ActionTriggerScope.TASK,
                        "complete")
                .orElseThrow();

        assertEquals("versioned", config.actionXml());
        assertEquals("order:1:123", config.processDefinitionId());
        assertEquals(ActionTriggerScope.TASK, config.triggerScope());
    }

    @Test
    void fallsBackToLegacyConfiguration() {
        insert("order", null, "approve", "TASK", "complete", "legacy");

        ActionConfigRepository.ActionConfig config = repository
                .findByDefinitionAndScope(
                        "order:2:456",
                        "order",
                        "approve",
                        ActionTriggerScope.TASK,
                        "complete")
                .orElseThrow();

        assertEquals("legacy", config.actionXml());
    }

    @Test
    void keepsScopesIndependent() {
        insert("order", "order:1:123", "approve", "TASK", "complete", "task");
        insert("order", "order:1:123", "approve", "NODE", "complete", "node");

        ActionConfigRepository.ActionConfig nodeConfig = repository
                .findByDefinitionAndScope(
                        "order:1:123",
                        "order",
                        "approve",
                        ActionTriggerScope.NODE,
                        "complete")
                .orElseThrow();

        assertEquals("node", nodeConfig.actionXml());
        assertTrue(repository.findByDefinitionAndScope(
                "order:1:123",
                "order",
                "approve",
                ActionTriggerScope.PROCESS,
                "complete").isEmpty());
    }

    private void insert(
            String processKey,
            String processDefinitionId,
            String activityId,
            String scope,
            String event,
            String actionXml) {
        jdbcTemplate.update("""
                INSERT INTO t_node_action_config (
                    process_definition_key, process_definition_id, activity_id,
                    trigger_scope, event, action_xml, version, enabled
                ) VALUES (?, ?, ?, ?, ?, ?, 1, 1)
                """,
                processKey,
                processDefinitionId,
                activityId,
                scope,
                event,
                actionXml);
    }
}
