ALTER TABLE t_async_task
    ADD COLUMN tenant_id VARCHAR(64) NULL AFTER business_key,
    ADD COLUMN claim_token VARCHAR(64) NULL AFTER status,
    ADD COLUMN max_retry_count INT NOT NULL DEFAULT 5 AFTER retry_count,
    ADD COLUMN next_retry_time DATETIME NULL AFTER max_retry_count,
    MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        COMMENT '任务状态: PENDING, PROCESSING, SUCCESS, RETRY_WAIT, DEAD',
    ADD INDEX idx_status_next_retry (status, next_retry_time),
    ADD INDEX idx_status_update_time (status, update_time);

UPDATE t_async_task
SET status = 'DEAD'
WHERE status = 'FAILED';
