CREATE TABLE IF NOT EXISTS t_process_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  process_definition_key VARCHAR(64) NOT NULL,
  business_key_var VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS t_node_action_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  process_definition_key VARCHAR(64) NOT NULL,
  process_definition_id VARCHAR(128),
  activity_id VARCHAR(64) NOT NULL,
  trigger_scope VARCHAR(16) DEFAULT 'TASK' NOT NULL,
  event VARCHAR(32) NOT NULL,
  action_xml TEXT NOT NULL,
  version INT DEFAULT 1,
  enabled INT DEFAULT 1,
  remark VARCHAR(255),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS t_async_task (
  task_id VARCHAR(64) PRIMARY KEY,
  business_key VARCHAR(128) NOT NULL,
  tenant_id VARCHAR(64),
  action_type VARCHAR(32) NOT NULL,
  payload_json CLOB NOT NULL,
  result_json CLOB,
  status VARCHAR(20) NOT NULL,
  claim_token VARCHAR(64),
  retry_count INT DEFAULT 0 NOT NULL,
  max_retry_count INT DEFAULT 5 NOT NULL,
  next_retry_time TIMESTAMP,
  error_msg CLOB,
  create_time TIMESTAMP NOT NULL,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS t_process_instance_mirror (
  process_instance_id VARCHAR(64) PRIMARY KEY,
  root_process_instance_id VARCHAR(64),
  parent_process_instance_id VARCHAR(64),
  process_definition_id VARCHAR(128) NOT NULL,
  process_definition_key VARCHAR(64) NOT NULL,
  process_definition_version INT,
  process_name VARCHAR(255),
  business_key VARCHAR(128),
  tenant_id VARCHAR(64),
  process_status VARCHAR(20) NOT NULL,
  active_task_count INT DEFAULT 0 NOT NULL,
  current_node_names VARCHAR(1000),
  current_handler_names VARCHAR(1000),
  start_user_id VARCHAR(64),
  start_user_name VARCHAR(255),
  start_time TIMESTAMP,
  end_time TIMESTAMP,
  last_event_time TIMESTAMP,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS t_process_task_mirror (
  task_id VARCHAR(64) PRIMARY KEY,
  process_instance_id VARCHAR(64) NOT NULL,
  process_definition_key VARCHAR(64) NOT NULL,
  business_key VARCHAR(128),
  tenant_id VARCHAR(64),
  activity_id VARCHAR(64),
  node_name VARCHAR(255),
  task_name VARCHAR(255),
  task_status VARCHAR(20) NOT NULL,
  task_kind VARCHAR(20),
  assignee_id VARCHAR(64),
  assignee_name VARCHAR(255),
  owner_id VARCHAR(64),
  owner_name VARCHAR(255),
  candidate_user_ids VARCHAR(1000),
  candidate_user_names VARCHAR(1000),
  candidate_group_ids VARCHAR(1000),
  candidate_group_names VARCHAR(1000),
  sign_group_id VARCHAR(64),
  sign_round INT,
  sign_order INT,
  completion_policy VARCHAR(32),
  vote VARCHAR(20),
  task_create_time TIMESTAMP,
  claim_time TIMESTAMP,
  due_time TIMESTAMP,
  complete_time TIMESTAMP,
  duration_ms BIGINT,
  last_event_time TIMESTAMP,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS t_process_activity_log (
  event_id VARCHAR(64) PRIMARY KEY,
  event_scope VARCHAR(16) NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  process_instance_id VARCHAR(64) NOT NULL,
  process_definition_key VARCHAR(64),
  process_name VARCHAR(255),
  business_key VARCHAR(128),
  tenant_id VARCHAR(64),
  task_id VARCHAR(64),
  activity_id VARCHAR(64),
  node_name VARCHAR(255),
  actor_id VARCHAR(64),
  actor_name VARCHAR(255),
  from_assignee_id VARCHAR(64),
  from_assignee_name VARCHAR(255),
  to_assignee_id VARCHAR(64),
  to_assignee_name VARCHAR(255),
  opinion VARCHAR(2000),
  event_time TIMESTAMP NOT NULL,
  task_create_time TIMESTAMP,
  duration_ms BIGINT,
  related_task_id VARCHAR(64),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS t_process_business_link (
  link_id VARCHAR(64) PRIMARY KEY,
  process_instance_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(64),
  system_code VARCHAR(64),
  business_type VARCHAR(64),
  business_id VARCHAR(128),
  business_key VARCHAR(128),
  business_title VARCHAR(255),
  relation_type VARCHAR(32),
  business_sync_status VARCHAR(32),
  business_url VARCHAR(1000),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS t_process_definition_catalog (
  process_definition_id VARCHAR(128) PRIMARY KEY,
  tenant_id VARCHAR(64),
  process_key VARCHAR(64) NOT NULL,
  version_number INT NOT NULL,
  process_name VARCHAR(255),
  deployment_id VARCHAR(128),
  source_xml CLOB,
  semantic_json CLOB,
  status VARCHAR(20) DEFAULT 'PUBLISHED' NOT NULL,
  published_time TIMESTAMP,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE OR REPLACE VIEW v_process_instance AS
SELECT * FROM t_process_instance_mirror;

CREATE OR REPLACE VIEW v_process_task AS
SELECT * FROM t_process_task_mirror;

CREATE OR REPLACE VIEW v_process_activity AS
SELECT * FROM t_process_activity_log;

CREATE OR REPLACE VIEW v_process_business AS
SELECT * FROM t_process_business_link;

CREATE OR REPLACE VIEW v_process_definition AS
SELECT * FROM t_process_definition_catalog;
