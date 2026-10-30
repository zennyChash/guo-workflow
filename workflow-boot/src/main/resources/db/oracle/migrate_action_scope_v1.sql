ALTER TABLE t_node_action_config ADD (
    process_definition_id VARCHAR2(128),
    trigger_scope VARCHAR2(16) DEFAULT 'TASK' NOT NULL
);

ALTER TABLE t_node_action_config DROP CONSTRAINT uk_def_act_evt;

ALTER TABLE t_node_action_config ADD CONSTRAINT uk_action_scope UNIQUE
    (process_definition_id, activity_id, trigger_scope, event);

COMMENT ON COLUMN t_node_action_config.process_definition_id IS '流程定义版本ID';
COMMENT ON COLUMN t_node_action_config.trigger_scope IS '触发作用域: TASK/NODE/PROCESS';

COMMIT;
