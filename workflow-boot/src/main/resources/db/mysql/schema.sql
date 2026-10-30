CREATE TABLE `t_async_task` (
  `task_id` varchar(64) NOT NULL COMMENT '异步任务唯一ID (UUID)',
  `business_key` varchar(128) NOT NULL COMMENT '业务主键 (如订单号)',
  `tenant_id` varchar(64) DEFAULT NULL COMMENT '租户ID',
  `action_type` varchar(32) NOT NULL COMMENT '执行器类型 (如 SQL, INSTRUCTION, SCRIPT)',
  `payload_json` json NOT NULL COMMENT '完整指令对象的 JSON 序列化数据',
  `result_json` json DEFAULT NULL COMMENT '动作执行产生的变量结果',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态: PENDING, PROCESSING, SUCCESS, RETRY_WAIT, DEAD',
  `claim_token` varchar(64) DEFAULT NULL COMMENT '抢占令牌',
  `retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
  `max_retry_count` int NOT NULL DEFAULT '5' COMMENT '最大重试次数',
  `next_retry_time` datetime DEFAULT NULL COMMENT '下次可执行时间',
  `error_msg` text DEFAULT NULL COMMENT '执行失败时的异常堆栈或错误信息',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`task_id`),
  KEY `idx_status_create_time` (`status`, `create_time`),
  KEY `idx_status_next_retry` (`status`, `next_retry_time`),
  KEY `idx_status_update_time` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务动作异步任务表';

CREATE TABLE `t_node_action_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `process_definition_key` varchar(64) NOT NULL COMMENT '流程定义Key',
  `process_definition_id` varchar(128) DEFAULT NULL COMMENT '流程定义版本ID',
  `activity_id` varchar(64) NOT NULL COMMENT '节点ID (BPMN的UserTask ID)',
  `trigger_scope` varchar(16) NOT NULL DEFAULT 'TASK' COMMENT '触发作用域: TASK/NODE/PROCESS',
  `event` varchar(32) NOT NULL COMMENT '触发事件',
  `action_xml` text NOT NULL COMMENT '动作配置XML',
  `version` int NOT NULL DEFAULT '1' COMMENT '版本号',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用: 1启用 0禁用',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_action_scope` (`process_definition_id`,`activity_id`,`trigger_scope`,`event`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流程节点动作配置表';

CREATE TABLE `t_process_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `process_definition_key` varchar(64) NOT NULL COMMENT '流程定义Key (BPMN ID)',
  `business_key_var` varchar(64) NOT NULL COMMENT '业务主键变量名 (如 orderNo)',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_process_key` (`process_definition_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流程定义全局配置表';

CREATE TABLE `t_process_instance_mirror` (
  `process_instance_id` varchar(64) NOT NULL,
  `root_process_instance_id` varchar(64) DEFAULT NULL,
  `parent_process_instance_id` varchar(64) DEFAULT NULL,
  `process_definition_id` varchar(128) NOT NULL,
  `process_definition_key` varchar(64) NOT NULL,
  `process_definition_version` int DEFAULT NULL,
  `process_name` varchar(255) DEFAULT NULL,
  `business_key` varchar(128) DEFAULT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `process_status` varchar(20) NOT NULL,
  `active_task_count` int NOT NULL DEFAULT '0',
  `current_node_names` varchar(1000) DEFAULT NULL,
  `current_handler_names` varchar(1000) DEFAULT NULL,
  `start_user_id` varchar(64) DEFAULT NULL,
  `start_user_name` varchar(255) DEFAULT NULL,
  `start_time` datetime DEFAULT NULL,
  `end_time` datetime DEFAULT NULL,
  `last_event_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`process_instance_id`),
  KEY `idx_mirror_instance_business` (`business_key`, `process_status`),
  KEY `idx_mirror_instance_tenant_business` (`tenant_id`, `business_key`),
  KEY `idx_mirror_instance_status_time` (`process_status`, `update_time`),
  KEY `idx_mirror_instance_start_user` (`start_user_id`, `start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流程实例当前状态镜像';

CREATE TABLE `t_process_task_mirror` (
  `task_id` varchar(64) NOT NULL,
  `process_instance_id` varchar(64) NOT NULL,
  `process_definition_key` varchar(64) NOT NULL,
  `business_key` varchar(128) DEFAULT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `activity_id` varchar(64) DEFAULT NULL,
  `node_name` varchar(255) DEFAULT NULL,
  `task_name` varchar(255) DEFAULT NULL,
  `task_status` varchar(20) NOT NULL,
  `task_kind` varchar(20) DEFAULT NULL,
  `assignee_id` varchar(64) DEFAULT NULL,
  `assignee_name` varchar(255) DEFAULT NULL,
  `owner_id` varchar(64) DEFAULT NULL,
  `owner_name` varchar(255) DEFAULT NULL,
  `candidate_user_ids` varchar(1000) DEFAULT NULL,
  `candidate_user_names` varchar(1000) DEFAULT NULL,
  `candidate_group_ids` varchar(1000) DEFAULT NULL,
  `candidate_group_names` varchar(1000) DEFAULT NULL,
  `sign_group_id` varchar(64) DEFAULT NULL,
  `sign_round` int DEFAULT NULL,
  `sign_order` int DEFAULT NULL,
  `completion_policy` varchar(32) DEFAULT NULL,
  `vote` varchar(20) DEFAULT NULL,
  `task_create_time` datetime DEFAULT NULL,
  `claim_time` datetime DEFAULT NULL,
  `due_time` datetime DEFAULT NULL,
  `complete_time` datetime DEFAULT NULL,
  `duration_ms` bigint DEFAULT NULL,
  `last_event_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`task_id`),
  KEY `idx_mirror_task_process_status` (`process_instance_id`, `task_status`),
  KEY `idx_mirror_task_business_status` (`business_key`, `task_status`),
  KEY `idx_mirror_task_assignee_due` (`assignee_id`, `task_status`, `due_time`),
  KEY `idx_mirror_task_sign_group` (`sign_group_id`, `task_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流程任务当前和历史镜像';

CREATE TABLE `t_process_activity_log` (
  `event_id` varchar(64) NOT NULL,
  `event_scope` varchar(16) NOT NULL,
  `event_type` varchar(32) NOT NULL,
  `process_instance_id` varchar(64) NOT NULL,
  `process_definition_key` varchar(64) DEFAULT NULL,
  `process_name` varchar(255) DEFAULT NULL,
  `business_key` varchar(128) DEFAULT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `task_id` varchar(64) DEFAULT NULL,
  `activity_id` varchar(64) DEFAULT NULL,
  `node_name` varchar(255) DEFAULT NULL,
  `actor_id` varchar(64) DEFAULT NULL,
  `actor_name` varchar(255) DEFAULT NULL,
  `from_assignee_id` varchar(64) DEFAULT NULL,
  `from_assignee_name` varchar(255) DEFAULT NULL,
  `to_assignee_id` varchar(64) DEFAULT NULL,
  `to_assignee_name` varchar(255) DEFAULT NULL,
  `opinion` varchar(2000) DEFAULT NULL,
  `event_time` datetime NOT NULL,
  `task_create_time` datetime DEFAULT NULL,
  `duration_ms` bigint DEFAULT NULL,
  `related_task_id` varchar(64) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`),
  KEY `idx_mirror_activity_process_time` (`process_instance_id`, `event_time`),
  KEY `idx_mirror_activity_business_time` (`business_key`, `event_time`),
  KEY `idx_mirror_activity_task_time` (`task_id`, `event_time`),
  KEY `idx_mirror_activity_actor_time` (`actor_id`, `event_time`),
  KEY `idx_mirror_activity_type_time` (`event_type`, `event_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流程活动和审批轨迹';

CREATE TABLE `t_process_business_link` (
  `link_id` varchar(64) NOT NULL,
  `process_instance_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `system_code` varchar(64) DEFAULT NULL,
  `business_type` varchar(64) DEFAULT NULL,
  `business_id` varchar(128) DEFAULT NULL,
  `business_key` varchar(128) DEFAULT NULL,
  `business_title` varchar(255) DEFAULT NULL,
  `relation_type` varchar(32) DEFAULT NULL,
  `business_sync_status` varchar(32) DEFAULT NULL,
  `business_url` varchar(1000) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`link_id`),
  KEY `idx_mirror_link_process` (`process_instance_id`),
  KEY `idx_mirror_link_business` (`system_code`, `business_type`, `business_id`),
  KEY `idx_mirror_link_tenant_business` (`tenant_id`, `business_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流程与业务单据关联';

CREATE TABLE `t_process_definition_catalog` (
  `process_definition_id` varchar(128) NOT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `process_key` varchar(64) NOT NULL,
  `version_number` int NOT NULL,
  `process_name` varchar(255) DEFAULT NULL,
  `deployment_id` varchar(128) DEFAULT NULL,
  `source_xml` longtext DEFAULT NULL,
  `semantic_json` longtext DEFAULT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PUBLISHED',
  `published_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`process_definition_id`),
  KEY `idx_mirror_definition_tenant_key` (`tenant_id`, `process_key`, `version_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流程定义版本目录';

CREATE OR REPLACE VIEW `v_process_instance` AS
SELECT * FROM `t_process_instance_mirror`;

CREATE OR REPLACE VIEW `v_process_task` AS
SELECT * FROM `t_process_task_mirror`;

CREATE OR REPLACE VIEW `v_process_activity` AS
SELECT * FROM `t_process_activity_log`;

CREATE OR REPLACE VIEW `v_process_business` AS
SELECT * FROM `t_process_business_link`;

CREATE OR REPLACE VIEW `v_process_definition` AS
SELECT * FROM `t_process_definition_catalog`;
