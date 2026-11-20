-- payment-marketing · MySQL（pp_marketing）。docker profile sql.init.mode=never，本脚本供空库一次性初始化参考。
-- 库本身由 MarketingDatabase.ensureDatabase() 在 Spring 上下文前 additive 创建
-- （CREATE DATABASE IF NOT EXISTS pp_marketing），不改 ops-pro/deploy/payment-databases.sql。
-- 业务表 DDL 单一事实来源在 com.qoder.payment.marketing.config.MarketingSchema（启动期幂等补建，只增不改）。
CREATE TABLE IF NOT EXISTS sequences (
    name  VARCHAR(64) PRIMARY KEY,
    value BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_marketing', 0);
DROP FUNCTION IF EXISTS next_value_for;
DELIMITER //
CREATE FUNCTION next_value_for(s VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) RETURNS BIGINT
MODIFIES SQL DATA
BEGIN
    UPDATE sequences SET value = LAST_INSERT_ID(value + 1) WHERE name = s;
    RETURN LAST_INSERT_ID();
END//
DELIMITER ;

CREATE TABLE IF NOT EXISTS idempotent_record (
    biz_type   VARCHAR(32) NOT NULL,
    biz_no     VARCHAR(64) NOT NULL,
    result     LONGTEXT,
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (biz_type, biz_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until BIGINT       NOT NULL,
    locked_at  BIGINT       NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;