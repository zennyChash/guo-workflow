ALTER TABLE t_async_task
    MODIFY COLUMN business_key VARCHAR(128) NOT NULL
        COMMENT '业务主键 (如订单号)';
