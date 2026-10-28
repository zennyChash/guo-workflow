CREATE TABLE IF NOT EXISTS t_process_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  process_definition_key VARCHAR(64) NOT NULL,
  business_key_var VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS t_node_action_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  process_definition_key VARCHAR(64) NOT NULL,
  activity_id VARCHAR(64) NOT NULL,
  event VARCHAR(32) NOT NULL,
  action_xml TEXT NOT NULL,
  version INT DEFAULT 1,
  enabled INT DEFAULT 1,
  remark VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS t_async_task (
  task_id VARCHAR(64) PRIMARY KEY,
  business_key VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(64),
  action_type VARCHAR(32) NOT NULL,
  payload_json CLOB NOT NULL,
  status VARCHAR(20) NOT NULL,
  claim_token VARCHAR(64),
  retry_count INT DEFAULT 0 NOT NULL,
  max_retry_count INT DEFAULT 5 NOT NULL,
  next_retry_time TIMESTAMP,
  error_msg CLOB,
  create_time TIMESTAMP NOT NULL,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
