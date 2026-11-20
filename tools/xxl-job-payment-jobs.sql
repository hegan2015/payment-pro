-- I-3 分布式调度：payment-pro 执行器分组 + 任务记录（共享栈 xxl_job 库，additive 幂等）
--
-- 红线：只 INSERT 不存在的行，绝不 UPDATE/DELETE 既有分组与任务（claim-pro-executor 及其 3 条任务保持原样）。
-- 执行方式（口令从 ~/.config/payment-pro/docker.env 取，不回显）：
--   set -a; . ~/.config/payment-pro/docker.env; set +a
--   /Users/hegan/.docker/bin/docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" claim-shared-mysql \
--     mysql -uroot xxl_job < tools/xxl-job-payment-jobs.sql
--
-- 调度语义：XXL-Job 只负责触发，幂等/互斥由 ShedLock + 既有幂等模板保证。
-- cron 与 local @Scheduled 的 fixedDelay 对齐（recover 5s / outbox 3s / notify 3s），
-- 调度中心宕机时 LocalScheduledRunner 自动回切本地兜底，补偿不停摆。
-- reconRun 建记录但 trigger_status=0（停）：周期性日切分片对账属 RC-5，届时再启用 cron，
-- 避免凭空增加背景对账报告/差错单影响既有断言。

-- ---------- 执行器分组（address_type=0 自动注册） ----------
INSERT INTO xxl_job_group(app_name, title, address_type, address_list, update_time)
SELECT 'payment-executor', '支付执行器', 0, NULL, NOW()
WHERE NOT EXISTS (SELECT 1 FROM (SELECT app_name FROM xxl_job_group) t WHERE t.app_name = 'payment-executor');

INSERT INTO xxl_job_group(app_name, title, address_type, address_list, update_time)
SELECT 'payment-notify-executor', '通知执行器', 0, NULL, NOW()
WHERE NOT EXISTS (SELECT 1 FROM (SELECT app_name FROM xxl_job_group) t WHERE t.app_name = 'payment-notify-executor');

INSERT INTO xxl_job_group(app_name, title, address_type, address_list, update_time)
SELECT 'payment-recon-executor', '对账执行器', 0, NULL, NOW()
WHERE NOT EXISTS (SELECT 1 FROM (SELECT app_name FROM xxl_job_group) t WHERE t.app_name = 'payment-recon-executor');

-- ---------- 任务记录（BEAN 模式，JobHandler 名与 SPI 一致） ----------
-- TCC 二阶段恢复：5s，串行执行，misfire 立即补一次，失败不重试（TCC 自身退避重试）
INSERT INTO xxl_job_info(job_group, job_desc, add_time, update_time, author, alarm_email,
                         schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
                         executor_handler, executor_param, executor_block_strategy, executor_timeout,
                         executor_fail_retry_count, glue_type, glue_remark, glue_updatetime,
                         trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, 'I-3 TCC 二阶段恢复', NOW(), NOW(), 'payment-pro', '',
       'CRON', '0/5 * * * * ?', 'FIRE_ONCE_NOW', 'FIRST',
       'recoverTransactions', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', 'GLUE代码初始化', NOW(), 1, 0, 0
FROM xxl_job_group g
WHERE g.app_name = 'payment-executor'
  AND NOT EXISTS (SELECT 1 FROM (SELECT executor_handler FROM xxl_job_info) t
                  WHERE t.executor_handler = 'recoverTransactions');

-- outbox 可靠投递兜底：3s（轮询兜底永不删除，红线）
INSERT INTO xxl_job_info(job_group, job_desc, add_time, update_time, author, alarm_email,
                         schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
                         executor_handler, executor_param, executor_block_strategy, executor_timeout,
                         executor_fail_retry_count, glue_type, glue_remark, glue_updatetime,
                         trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, 'I-3 outbox 可靠投递', NOW(), NOW(), 'payment-pro', '',
       'CRON', '0/3 * * * * ?', 'FIRE_ONCE_NOW', 'FIRST',
       'dispatchOutbox', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', 'GLUE代码初始化', NOW(), 1, 0, 0
FROM xxl_job_group g
WHERE g.app_name = 'payment-executor'
  AND NOT EXISTS (SELECT 1 FROM (SELECT executor_handler FROM xxl_job_info) t
                  WHERE t.executor_handler = 'dispatchOutbox');

-- 商户通知推送：3s
INSERT INTO xxl_job_info(job_group, job_desc, add_time, update_time, author, alarm_email,
                         schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
                         executor_handler, executor_param, executor_block_strategy, executor_timeout,
                         executor_fail_retry_count, glue_type, glue_remark, glue_updatetime,
                         trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, 'I-3 商户通知推送', NOW(), NOW(), 'payment-pro', '',
       'CRON', '0/3 * * * * ?', 'FIRE_ONCE_NOW', 'FIRST',
       'notifyPush', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', 'GLUE代码初始化', NOW(), 1, 0, 0
FROM xxl_job_group g
WHERE g.app_name = 'payment-notify-executor'
  AND NOT EXISTS (SELECT 1 FROM (SELECT executor_handler FROM xxl_job_info) t
                  WHERE t.executor_handler = 'notifyPush');

-- 分片对账：分片广播（SHARDING_BROADCAST），记录建好但暂不启用（RC-5 日切对账再开 cron）
INSERT INTO xxl_job_info(job_group, job_desc, add_time, update_time, author, alarm_email,
                         schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
                         executor_handler, executor_param, executor_block_strategy, executor_timeout,
                         executor_fail_retry_count, glue_type, glue_remark, glue_updatetime,
                         trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, 'I-3 分片对账(RC-5 待启用)', NOW(), NOW(), 'payment-pro', '',
       'CRON', '0 0 3 * * ?', 'DO_NOTHING', 'SHARDING_BROADCAST',
       'reconRun', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', 'GLUE代码初始化', NOW(), 0, 0, 0
FROM xxl_job_group g
WHERE g.app_name = 'payment-recon-executor'
  AND NOT EXISTS (SELECT 1 FROM (SELECT executor_handler FROM xxl_job_info) t
                  WHERE t.executor_handler = 'reconRun');