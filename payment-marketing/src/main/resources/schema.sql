-- payment-marketing · local H2（data/marketing，零依赖）。
-- 引导对象：取号序列 + H2 别名 + 幂等表 + ShedLock 锁表。
-- 业务表（marketing_template / coupon / red_packet / marketing_claim / coupon_use / red_packet_use /
-- points_account / points_batch / points_log / activity / activity_use / marketing_alloc / marketing_audit）
-- 的 DDL 单一事实来源在 com.qoder.payment.marketing.config.MarketingSchema（H2/MySQL 双方言、幂等、只增不改），
-- 由 MarketingSchemaUpgrade 在 Bean 初始化阶段（早于 Dubbo 服务导出）执行，local/docker 同构。
CREATE SEQUENCE IF NOT EXISTS seq_marketing START WITH 1;
CREATE ALIAS IF NOT EXISTS next_value_for FOR "com.qoder.payment.common.db.H2Sequence.next";

CREATE TABLE IF NOT EXISTS idempotent_record (
    biz_type   VARCHAR(32) NOT NULL,
    biz_no     VARCHAR(64) NOT NULL,
    result     CLOB,
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (biz_type, biz_no)
);

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until BIGINT       NOT NULL,
    locked_at  BIGINT       NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);