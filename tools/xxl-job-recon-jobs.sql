-- P1-J 对账工作流（RC-2/RC-5）：XXL-Job 任务记录（共享栈 xxl_job 库，additive 幂等）
--
-- 红线：只 INSERT 不存在的行，绝不 UPDATE/DELETE 既有分组与任务。
-- 执行方式（口令从 ~/.config/payment-pro/docker.env 取，不回显）：
--   set -a; . ~/.config/payment-pro/docker.env; set +a
--   /Users/hegan/.docker/bin/docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" claim-shared-mysql \
--     mysql -uroot xxl_job < tools/xxl-job-recon-jobs.sql
--
-- 调度语义：XXL-Job 只负责触发，幂等/互斥由 ShedLock（reconDaily:<day>:<i>/<n> 逐片锁）+
-- 批次唯一键 + 差错单幂等键保证。两条任务均 trigger_status=0（建好待启用）——
-- **默认不自动跑批**，与 reconRun/accountingDayCut 先例一致；启用 cron 须用户/主会话拍板。
-- reconDaily 路由策略 SHARDING_BROADCAST：分片广播 shardIndex/shardTotal 与
-- ReconService.shardOf（CRC32 取模）一致，各片不重不漏，全局聚合项只由分片 0 核对。

-- RC-2/RC-5 日切批量对账（分片广播；param 空=当前账面会计日，可填 epoch millis 或 yyyyMMdd）
INSERT INTO xxl_job_info(job_group, job_desc, add_time, update_time, author, alarm_email,
                         schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
                         executor_handler, executor_param, executor_block_strategy, executor_timeout,
                         executor_fail_retry_count, glue_type, glue_remark, glue_updatetime,
                         trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, 'RC-2 日切批量对账(RC-5 分片广播,待启用)', NOW(), NOW(), 'payment-pro', '',
       'CRON', '0 30 3 * * ?', 'DO_NOTHING', 'SHARDING_BROADCAST',
       'reconDaily', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', 'GLUE代码初始化', NOW(), 0, 0, 0
FROM xxl_job_group g
WHERE g.app_name = 'payment-recon-executor'
  AND NOT EXISTS (SELECT 1 FROM (SELECT executor_handler FROM xxl_job_info) t
                  WHERE t.executor_handler = 'reconDaily');

-- RC-3 渠道内外对账（param 格式 <channelCode>[:<epochMillis|yyyyMMdd>]，空=MOCK_BANK+当前账面会计日）
INSERT INTO xxl_job_info(job_group, job_desc, add_time, update_time, author, alarm_email,
                         schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
                         executor_handler, executor_param, executor_block_strategy, executor_timeout,
                         executor_fail_retry_count, glue_type, glue_remark, glue_updatetime,
                         trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, 'RC-3 渠道内外对账(待启用)', NOW(), NOW(), 'payment-pro', '',
       'CRON', '0 0 4 * * ?', 'DO_NOTHING', 'FIRST',
       'reconChannel', 'MOCK_BANK', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', 'GLUE代码初始化', NOW(), 0, 0, 0
FROM xxl_job_group g
WHERE g.app_name = 'payment-recon-executor'
  AND NOT EXISTS (SELECT 1 FROM (SELECT executor_handler FROM xxl_job_info) t
                  WHERE t.executor_handler = 'reconChannel');