-- 1. 创建表
CREATE TABLE T_ASYNC_TASK (
    TASK_ID VARCHAR2(64) NOT NULL,
    BUSINESS_KEY VARCHAR2(128) NOT NULL,
    TENANT_ID VARCHAR2(64),
    ACTION_TYPE VARCHAR2(32) NOT NULL,
    PAYLOAD_JSON CLOB NOT NULL,
    RESULT_JSON CLOB,
    STATUS VARCHAR2(20) DEFAULT 'PENDING' NOT NULL,
    CLAIM_TOKEN VARCHAR2(64),
    RETRY_COUNT NUMBER(10) DEFAULT 0 NOT NULL,
    MAX_RETRY_COUNT NUMBER(10) DEFAULT 5 NOT NULL,
    NEXT_RETRY_TIME TIMESTAMP,
    ERROR_MSG CLOB,
    CREATE_TIME TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UPDATE_TIME TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT PK_T_ASYNC_TASK PRIMARY KEY (TASK_ID)
);

-- 2. 创建索引 (用于消费者轮询)
CREATE INDEX IDX_ASYNC_TASK_STATUS_TIME ON T_ASYNC_TASK (STATUS, CREATE_TIME);
CREATE INDEX IDX_ASYNC_TASK_STATUS_RETRY ON T_ASYNC_TASK (STATUS, NEXT_RETRY_TIME);
CREATE INDEX IDX_ASYNC_TASK_STATUS_UPDATE ON T_ASYNC_TASK (STATUS, UPDATE_TIME);

-- 3. 添加表注释
COMMENT ON TABLE T_ASYNC_TASK IS '业务动作异步任务表';

-- 4. 添加字段注释
COMMENT ON COLUMN T_ASYNC_TASK.TASK_ID IS '异步任务唯一ID (UUID)';
COMMENT ON COLUMN T_ASYNC_TASK.BUSINESS_KEY IS '业务主键 (如订单号)';
COMMENT ON COLUMN T_ASYNC_TASK.ACTION_TYPE IS '执行器类型 (如 SQL, INSTRUCTION, SCRIPT)';
COMMENT ON COLUMN T_ASYNC_TASK.PAYLOAD_JSON IS '完整指令对象的 JSON 序列化数据';
COMMENT ON COLUMN T_ASYNC_TASK.RESULT_JSON IS '动作执行产生的变量结果';
COMMENT ON COLUMN T_ASYNC_TASK.TENANT_ID IS '租户ID';
COMMENT ON COLUMN T_ASYNC_TASK.CLAIM_TOKEN IS '抢占令牌';
COMMENT ON COLUMN T_ASYNC_TASK.STATUS IS '任务状态: PENDING, PROCESSING, SUCCESS, RETRY_WAIT, DEAD';
COMMENT ON COLUMN T_ASYNC_TASK.RETRY_COUNT IS '重试次数';
COMMENT ON COLUMN T_ASYNC_TASK.MAX_RETRY_COUNT IS '最大重试次数';
COMMENT ON COLUMN T_ASYNC_TASK.NEXT_RETRY_TIME IS '下次可执行时间';
COMMENT ON COLUMN T_ASYNC_TASK.ERROR_MSG IS '执行失败时的异常堆栈或错误信息';
COMMENT ON COLUMN T_ASYNC_TASK.CREATE_TIME IS '创建时间';
COMMENT ON COLUMN T_ASYNC_TASK.UPDATE_TIME IS '更新时间';

CREATE TABLE t_node_action_config (
  id NUMBER(19) NOT NULL,
  process_definition_key VARCHAR2(64) NOT NULL,
  process_definition_id VARCHAR2(128),
  activity_id VARCHAR2(64) NOT NULL,
  trigger_scope VARCHAR2(16) DEFAULT 'TASK' NOT NULL,
  event VARCHAR2(32) NOT NULL,
  action_xml CLOB NOT NULL,
  version NUMBER(10) DEFAULT 1 NOT NULL,
  enabled NUMBER(1) DEFAULT 1 NOT NULL,
  remark VARCHAR2(255),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT pk_node_action_config PRIMARY KEY (id),
  CONSTRAINT uk_action_scope UNIQUE
    (process_definition_id, activity_id, trigger_scope, event)
);

-- 序列
CREATE SEQUENCE seq_node_action_config START WITH 1 INCREMENT BY 1 NOCACHE;

-- 触发器（实现自增）
CREATE OR REPLACE TRIGGER trg_node_action_config_id
BEFORE INSERT ON t_node_action_config
FOR EACH ROW
BEGIN
  IF :NEW.id IS NULL THEN
    SELECT seq_node_action_config.NEXTVAL INTO :NEW.id FROM dual;
  END IF;
END;
/

CREATE TABLE t_process_config (
  id NUMBER(19) NOT NULL,
  process_definition_key VARCHAR2(64) NOT NULL,
  business_key_var VARCHAR2(64) NOT NULL,
  remark VARCHAR2(255),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT pk_process_config PRIMARY KEY (id),
  CONSTRAINT uk_process_key UNIQUE (process_definition_key)
);

CREATE SEQUENCE seq_process_config START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE OR REPLACE TRIGGER trg_process_config_id
BEFORE INSERT ON t_process_config
FOR EACH ROW
BEGIN
  IF :NEW.id IS NULL THEN
    SELECT seq_process_config.NEXTVAL INTO :NEW.id FROM dual;
  END IF;
END;
/

CREATE TABLE t_process_instance_mirror (
    process_instance_id VARCHAR2(64) NOT NULL,
    root_process_instance_id VARCHAR2(64),
    parent_process_instance_id VARCHAR2(64),
    process_definition_id VARCHAR2(128) NOT NULL,
    process_definition_key VARCHAR2(64) NOT NULL,
    process_definition_version NUMBER(10),
    process_name VARCHAR2(255),
    business_key VARCHAR2(128),
    tenant_id VARCHAR2(64),
    process_status VARCHAR2(20) NOT NULL,
    active_task_count NUMBER(10) DEFAULT 0 NOT NULL,
    current_node_names VARCHAR2(1000),
    current_handler_names VARCHAR2(1000),
    start_user_id VARCHAR2(64),
    start_user_name VARCHAR2(255),
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    last_event_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_process_instance_mirror PRIMARY KEY (process_instance_id)
);

CREATE INDEX idx_pi_business
    ON t_process_instance_mirror (business_key, process_status);
CREATE INDEX idx_pi_tenant_biz
    ON t_process_instance_mirror (tenant_id, business_key);
CREATE INDEX idx_pi_status_time
    ON t_process_instance_mirror (process_status, update_time);
CREATE INDEX idx_pi_start_user
    ON t_process_instance_mirror (start_user_id, start_time);

CREATE TABLE t_process_task_mirror (
    task_id VARCHAR2(64) NOT NULL,
    process_instance_id VARCHAR2(64) NOT NULL,
    process_definition_key VARCHAR2(64) NOT NULL,
    business_key VARCHAR2(128),
    tenant_id VARCHAR2(64),
    activity_id VARCHAR2(64),
    node_name VARCHAR2(255),
    task_name VARCHAR2(255),
    task_status VARCHAR2(20) NOT NULL,
    task_kind VARCHAR2(20),
    assignee_id VARCHAR2(64),
    assignee_name VARCHAR2(255),
    owner_id VARCHAR2(64),
    owner_name VARCHAR2(255),
    candidate_user_ids VARCHAR2(1000),
    candidate_user_names VARCHAR2(1000),
    candidate_group_ids VARCHAR2(1000),
    candidate_group_names VARCHAR2(1000),
    sign_group_id VARCHAR2(64),
    sign_round NUMBER(10),
    sign_order NUMBER(10),
    completion_policy VARCHAR2(32),
    vote VARCHAR2(20),
    task_create_time TIMESTAMP,
    claim_time TIMESTAMP,
    due_time TIMESTAMP,
    complete_time TIMESTAMP,
    duration_ms NUMBER(19),
    last_event_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_process_task_mirror PRIMARY KEY (task_id)
);

CREATE INDEX idx_pt_process_status
    ON t_process_task_mirror (process_instance_id, task_status);
CREATE INDEX idx_pt_business_status
    ON t_process_task_mirror (business_key, task_status);
CREATE INDEX idx_pt_assignee_due
    ON t_process_task_mirror (assignee_id, task_status, due_time);
CREATE INDEX idx_pt_sign_group
    ON t_process_task_mirror (sign_group_id, task_status);

CREATE TABLE t_process_activity_log (
    event_id VARCHAR2(64) NOT NULL,
    event_scope VARCHAR2(16) NOT NULL,
    event_type VARCHAR2(32) NOT NULL,
    process_instance_id VARCHAR2(64) NOT NULL,
    process_definition_key VARCHAR2(64),
    process_name VARCHAR2(255),
    business_key VARCHAR2(128),
    tenant_id VARCHAR2(64),
    task_id VARCHAR2(64),
    activity_id VARCHAR2(64),
    node_name VARCHAR2(255),
    actor_id VARCHAR2(64),
    actor_name VARCHAR2(255),
    from_assignee_id VARCHAR2(64),
    from_assignee_name VARCHAR2(255),
    to_assignee_id VARCHAR2(64),
    to_assignee_name VARCHAR2(255),
    opinion VARCHAR2(2000),
    event_time TIMESTAMP NOT NULL,
    task_create_time TIMESTAMP,
    duration_ms NUMBER(19),
    related_task_id VARCHAR2(64),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_process_activity_log PRIMARY KEY (event_id)
);

CREATE INDEX idx_pa_process_time
    ON t_process_activity_log (process_instance_id, event_time);
CREATE INDEX idx_pa_business_time
    ON t_process_activity_log (business_key, event_time);
CREATE INDEX idx_pa_task_time
    ON t_process_activity_log (task_id, event_time);
CREATE INDEX idx_pa_actor_time
    ON t_process_activity_log (actor_id, event_time);
CREATE INDEX idx_pa_type_time
    ON t_process_activity_log (event_type, event_time);

CREATE TABLE t_process_business_link (
    link_id VARCHAR2(64) NOT NULL,
    process_instance_id VARCHAR2(64) NOT NULL,
    tenant_id VARCHAR2(64),
    system_code VARCHAR2(64),
    business_type VARCHAR2(64),
    business_id VARCHAR2(128),
    business_key VARCHAR2(128),
    business_title VARCHAR2(255),
    relation_type VARCHAR2(32),
    business_sync_status VARCHAR2(32),
    business_url VARCHAR2(1000),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_process_business_link PRIMARY KEY (link_id)
);

CREATE INDEX idx_pbl_process
    ON t_process_business_link (process_instance_id);
CREATE INDEX idx_pbl_business
    ON t_process_business_link (system_code, business_type, business_id);
CREATE INDEX idx_pbl_tenant_biz
    ON t_process_business_link (tenant_id, business_key);

CREATE TABLE t_process_definition_catalog (
    process_definition_id VARCHAR2(128) NOT NULL,
    tenant_id VARCHAR2(64),
    process_key VARCHAR2(64) NOT NULL,
    version_number NUMBER(10) NOT NULL,
    process_name VARCHAR2(255),
    deployment_id VARCHAR2(128),
    source_xml CLOB,
    semantic_json CLOB,
    status VARCHAR2(20) DEFAULT 'PUBLISHED' NOT NULL,
    published_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_process_definition_catalog PRIMARY KEY (process_definition_id)
);

CREATE INDEX idx_pdc_tenant_key
    ON t_process_definition_catalog (tenant_id, process_key, version_number);

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
