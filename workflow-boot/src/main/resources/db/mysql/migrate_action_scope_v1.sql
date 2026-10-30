ALTER TABLE t_node_action_config
    ADD COLUMN process_definition_id VARCHAR(128) NULL
        COMMENT '流程定义版本ID'
        AFTER process_definition_key,
    ADD COLUMN trigger_scope VARCHAR(16) NOT NULL DEFAULT 'TASK'
        COMMENT '触发作用域: TASK/NODE/PROCESS'
        AFTER activity_id,
    DROP INDEX uk_def_act_evt,
    ADD UNIQUE KEY uk_action_scope
        (process_definition_id, activity_id, trigger_scope, event);
