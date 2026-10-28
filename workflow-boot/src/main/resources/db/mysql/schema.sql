CREATE TABLE `t_async_task` (
  `task_id` varchar(64) NOT NULL COMMENT '异步任务唯一ID (UUID)',
  `business_key` varchar(64) NOT NULL COMMENT '业务主键 (如订单号)',
  `tenant_id` varchar(64) DEFAULT NULL COMMENT '租户ID',
  `action_type` varchar(32) NOT NULL COMMENT '执行器类型 (如 SQL, INSTRUCTION, SCRIPT)',
  `payload_json` json NOT NULL COMMENT '完整指令对象的 JSON 序列化数据',
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
  `activity_id` varchar(64) NOT NULL COMMENT '节点ID (BPMN的UserTask ID)',
  `event` varchar(32) NOT NULL COMMENT '触发事件: create/assignment/complete/delete',
  `action_xml` text NOT NULL COMMENT '动作配置XML',
  `version` int NOT NULL DEFAULT '1' COMMENT '版本号',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用: 1启用 0禁用',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_def_act_evt` (`process_definition_key`,`activity_id`,`event`)
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
