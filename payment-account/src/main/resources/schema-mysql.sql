-- payment-account · MySQL（pp_account）。幂等可重跑；docker profile sql.init.mode=never。
CREATE TABLE IF NOT EXISTS sequences (
    name  VARCHAR(64) PRIMARY KEY,
    value BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_account', 0);
DROP FUNCTION IF EXISTS next_value_for;
DELIMITER //
CREATE FUNCTION next_value_for(s VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) RETURNS BIGINT
MODIFIES SQL DATA
BEGIN
    UPDATE sequences SET value = LAST_INSERT_ID(value + 1) WHERE name = s;
    RETURN LAST_INSERT_ID();
END//
DELIMITER ;

CREATE TABLE IF NOT EXISTS account (
    id          VARCHAR(32) PRIMARY KEY,
    member_id   VARCHAR(32) NOT NULL,
    member_name VARCHAR(64),
    type        VARCHAR(16) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    created_at  BIGINT      NOT NULL,
    CONSTRAINT uk_account_member UNIQUE (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------- P1 C 泳道 additive（A-1/A-3/M-3）：列只增不删，既有行保留 ----------
-- MySQL 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 守卫（幂等可重跑）。
-- A-1 约束迁移 uk_account_member → uk_account_member_type 为计划 §3.I 明确要求（不删任何行）。
DROP PROCEDURE IF EXISTS pp_w3c_account_migrate;
DELIMITER //
CREATE PROCEDURE pp_w3c_account_migrate()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM information_schema.columns
                  WHERE table_schema=DATABASE() AND table_name='account' AND column_name='account_type') THEN
        ALTER TABLE account ADD COLUMN account_type VARCHAR(16) NOT NULL DEFAULT 'BALANCE';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM information_schema.columns
                  WHERE table_schema=DATABASE() AND table_name='account' AND column_name='level') THEN
        ALTER TABLE account ADD COLUMN level VARCHAR(8) NOT NULL DEFAULT 'I';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM information_schema.columns
                  WHERE table_schema=DATABASE() AND table_name='account' AND column_name='freeze_type') THEN
        ALTER TABLE account ADD COLUMN freeze_type VARCHAR(16) NOT NULL DEFAULT 'NONE';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM information_schema.columns
                  WHERE table_schema=DATABASE() AND table_name='account' AND column_name='freeze_reason') THEN
        ALTER TABLE account ADD COLUMN freeze_reason VARCHAR(256);
    END IF;
    IF NOT EXISTS(SELECT 1 FROM information_schema.columns
                  WHERE table_schema=DATABASE() AND table_name='account' AND column_name='freeze_at') THEN
        ALTER TABLE account ADD COLUMN freeze_at BIGINT;
    END IF;
    IF EXISTS(SELECT 1 FROM information_schema.statistics
              WHERE table_schema=DATABASE() AND table_name='account' AND index_name='uk_account_member') THEN
        ALTER TABLE account DROP INDEX uk_account_member;
    END IF;
    IF NOT EXISTS(SELECT 1 FROM information_schema.statistics
                  WHERE table_schema=DATABASE() AND table_name='account' AND index_name='uk_account_member_type') THEN
        ALTER TABLE account ADD UNIQUE KEY uk_account_member_type (member_id, account_type);
    END IF;
END//
DELIMITER ;
CALL pp_w3c_account_migrate();
DROP PROCEDURE IF EXISTS pp_w3c_account_migrate;

-- ---------- A-2 绑卡 ----------
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_card', 0);
CREATE TABLE IF NOT EXISTS bank_card (
    id           VARCHAR(32) PRIMARY KEY,
    account_id   VARCHAR(32) NOT NULL,
    card_no_hash VARCHAR(64) NOT NULL,
    card_no_mask VARCHAR(32),
    bank_name    VARCHAR(64),
    mobile_hash  VARCHAR(64),
    mobile_mask  VARCHAR(16),
    name_mask    VARCHAR(64),
    status       VARCHAR(16) NOT NULL,
    is_default   INT         NOT NULL DEFAULT 0,
    sign_no      VARCHAR(64),
    created_at   BIGINT      NOT NULL,
    unbound_at   BIGINT,
    UNIQUE KEY uk_bank_card (account_id, card_no_hash),
    KEY idx_bank_card_account (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- A-3 冻结细分 ----------
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_freeze', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_freeze_log', 0);
CREATE TABLE IF NOT EXISTS account_freeze (
    id             VARCHAR(32) PRIMARY KEY,
    account_id     VARCHAR(32) NOT NULL,
    amount         BIGINT      NOT NULL,
    reason_type    VARCHAR(16) NOT NULL,
    reason         VARCHAR(256),
    status         VARCHAR(16) NOT NULL,
    operator       VARCHAR(64),
    authorized_by  VARCHAR(64),
    biz_no         VARCHAR(64) NOT NULL,
    created_at     BIGINT      NOT NULL,
    released_at    BIGINT,
    release_biz_no VARCHAR(64),
    UNIQUE KEY uk_freeze_biz (biz_no),
    KEY idx_freeze_account (account_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS account_freeze_log (
    id            VARCHAR(32) PRIMARY KEY,
    account_id    VARCHAR(32) NOT NULL,
    action        VARCHAR(24) NOT NULL,
    freeze_type   VARCHAR(16),
    reason_type   VARCHAR(16),
    reason        VARCHAR(256),
    amount        BIGINT,
    operator      VARCHAR(64),
    authorized_by VARCHAR(64),
    biz_no        VARCHAR(64),
    created_at    BIGINT      NOT NULL,
    KEY idx_freeze_log_account (account_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- M-3 分级限额规则 ----------
CREATE TABLE IF NOT EXISTS account_level_rule (
    level         VARCHAR(8) PRIMARY KEY,
    single_limit  BIGINT NOT NULL,
    daily_limit   BIGINT NOT NULL,
    balance_limit BIGINT NOT NULL,
    require_kyc   VARCHAR(16) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- P2 C 泳道 additive（A-4 多币种）----------
-- docker sql.init.mode=never ⇒ 运行期由 AccountSchemaUpgrade 幂等执行同一套 DDL；
-- 此处同构声明供全新库一次性建表（只 ADD COLUMN / ADD UNIQUE KEY，绝不 DROP TABLE/DELETE 行）。
DROP PROCEDURE IF EXISTS pp_w3p2c_account_currency;
DELIMITER //
CREATE PROCEDURE pp_w3p2c_account_currency()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM information_schema.columns
                  WHERE table_schema=DATABASE() AND table_name='account' AND column_name='currency') THEN
        ALTER TABLE account ADD COLUMN currency VARCHAR(8) DEFAULT 'CNY';
    END IF;
    UPDATE account SET currency='CNY' WHERE currency IS NULL;
    IF NOT EXISTS(SELECT 1 FROM information_schema.statistics
                  WHERE table_schema=DATABASE() AND table_name='account' AND index_name='uk_account_member_type_ccy') THEN
        IF EXISTS(SELECT 1 FROM information_schema.statistics
                  WHERE table_schema=DATABASE() AND table_name='account' AND index_name='uk_account_member_type') THEN
            ALTER TABLE account DROP INDEX uk_account_member_type;
        END IF;
        ALTER TABLE account ADD UNIQUE KEY uk_account_member_type_ccy (member_id, account_type, currency);
    END IF;
END//
DELIMITER ;
CALL pp_w3p2c_account_currency();
DROP PROCEDURE IF EXISTS pp_w3p2c_account_currency;

-- ---------- P2 G 泳道 additive（A-5 开销户/升降级）----------
-- 运行期由 AccountSchemaUpgrade 幂等执行同一套 DDL；此处同构声明供全新库一次性建表。
-- 只 ADD COLUMN / CREATE TABLE IF NOT EXISTS，绝不 DROP TABLE/TRUNCATE/DELETE 既有行。
-- 销户是逻辑状态（lifecycle_status=CLOSED 且 status=CLOSED），账户行永久保留。
DROP PROCEDURE IF EXISTS pp_w3p2g_account_lifecycle;
DELIMITER //
CREATE PROCEDURE pp_w3p2g_account_lifecycle()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM information_schema.columns
                  WHERE table_schema=DATABASE() AND table_name='account' AND column_name='lifecycle_status') THEN
        ALTER TABLE account ADD COLUMN lifecycle_status VARCHAR(16) DEFAULT 'ACTIVE';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM information_schema.columns
                  WHERE table_schema=DATABASE() AND table_name='account' AND column_name='closed_at') THEN
        ALTER TABLE account ADD COLUMN closed_at BIGINT;
    END IF;
    UPDATE account SET lifecycle_status='ACTIVE' WHERE lifecycle_status IS NULL;
END//
DELIMITER ;
CALL pp_w3p2g_account_lifecycle();
DROP PROCEDURE IF EXISTS pp_w3p2g_account_lifecycle;

INSERT IGNORE INTO sequences(name, value) VALUES ('seq_account_lifecycle', 0);
CREATE TABLE IF NOT EXISTS account_lifecycle_log (
    id          VARCHAR(32) PRIMARY KEY,
    account_id  VARCHAR(32) NOT NULL,
    action      VARCHAR(24) NOT NULL,
    from_status VARCHAR(16),
    to_status   VARCHAR(16),
    from_level  VARCHAR(8),
    to_level    VARCHAR(8),
    operator    VARCHAR(64),
    reason      VARCHAR(256),
    biz_no      VARCHAR(64),
    created_at  BIGINT      NOT NULL,
    INDEX idx_lifecycle_account (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;