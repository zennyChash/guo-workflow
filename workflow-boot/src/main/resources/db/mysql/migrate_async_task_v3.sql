ALTER TABLE t_async_task
    ADD COLUMN result_json JSON NULL
        COMMENT '动作执行产生的变量结果'
        AFTER payload_json;
