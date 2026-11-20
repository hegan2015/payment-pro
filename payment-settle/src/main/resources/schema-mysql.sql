-- payment-settle · MySQL（pp_settle）。对空库执行一次；docker profile sql.init.mode=never。
CREATE TABLE IF NOT EXISTS sequences (
    name  VARCHAR(64) PRIMARY KEY,
    value BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_settle', 0);
DROP FUNCTION IF EXISTS next_value_for;
DELIMITER //
CREATE FUNCTION next_value_for(s VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) RETURNS BIGINT
MODIFIES SQL DATA
BEGIN
    UPDATE sequences SET value = LAST_INSERT_ID(value + 1) WHERE name = s;
    RETURN LAST_INSERT_ID();
END//
DELIMITER ;

CREATE TABLE IF NOT EXISTS settle_record (
    id          VARCHAR(32) PRIMARY KEY,
    type        VARCHAR(16) NOT NULL,
    biz_id      VARCHAR(64) NOT NULL,
    payment_id  VARCHAR(32) NOT NULL,
    order_id    VARCHAR(32),
    merchant_id VARCHAR(32) NOT NULL,
    gross       BIGINT      NOT NULL,
    fee         BIGINT      NOT NULL,
    net         BIGINT      NOT NULL,
    method      VARCHAR(16),
    status      VARCHAR(16) NOT NULL,
    created_at  BIGINT      NOT NULL,
    CONSTRAINT uk_settle_biz UNIQUE (biz_id, type),
    INDEX idx_settle_merchant (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS idempotent_record (
    biz_type   VARCHAR(32) NOT NULL,
    biz_no     VARCHAR(64) NOT NULL,
    result     LONGTEXT,
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (biz_type, biz_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================================
-- P1 第二期 ST-1~ST-5 新表 DDL 见 com.qoder.payment.settle.config.SettleSchema
-- （幂等 CREATE TABLE IF NOT EXISTS + seq_settle_st 种子行 + shedlock 锁表）。
-- 现存 pp_settle 库由 SettleShardPreBootstrap / SettleSchemaUpgrade 在启动期自动补建，
-- 无需手工执行本脚本、不改 ops-pro。全部新表单库不分片（见 application-docker.yml
-- payment.sharding.single-tables）；settle_record 与其分片表结构一字未改。
-- ============================================================================