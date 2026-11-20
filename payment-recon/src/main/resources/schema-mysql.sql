-- payment-recon · MySQL（pp_recon）。对空库执行一次；docker profile sql.init.mode=never。
CREATE TABLE IF NOT EXISTS sequences (
    name  VARCHAR(64) PRIMARY KEY,
    value BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_recon', 0);
DROP FUNCTION IF EXISTS next_value_for;
DELIMITER //
CREATE FUNCTION next_value_for(s VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) RETURNS BIGINT
MODIFIES SQL DATA
BEGIN
    UPDATE sequences SET value = LAST_INSERT_ID(value + 1) WHERE name = s;
    RETURN LAST_INSERT_ID();
END//
DELIMITER ;

CREATE TABLE IF NOT EXISTS recon_report (
    id         VARCHAR(32) PRIMARY KEY,
    checked_at BIGINT   NOT NULL,
    pass       BOOLEAN  NOT NULL,
    report     LONGTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- X-1 差错处理平台：差错单 + 处理审计（S-4/RC-1/N-2 共用载体）
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_dispute', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_dispute_fix', 0);

CREATE TABLE IF NOT EXISTS dispute_order (
    id          BIGINT       PRIMARY KEY,
    dispute_no  VARCHAR(32)  NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    source      VARCHAR(16)  NOT NULL,
    biz_no      VARCHAR(64)  NOT NULL,
    payment_id  VARCHAR(32),
    amount      BIGINT       NOT NULL DEFAULT 0,
    status      VARCHAR(16)  NOT NULL,
    strategy    VARCHAR(32),
    assignee    VARCHAR(64),
    reason      VARCHAR(512),
    fix_biz_no  VARCHAR(64),
    check_name  VARCHAR(64),
    biz_date    VARCHAR(8),
    created_at  BIGINT       NOT NULL,
    updated_at  BIGINT       NOT NULL,
    resolved_at BIGINT,
    UNIQUE KEY uk_dispute_no (dispute_no),
    INDEX idx_dispute_status (status),
    INDEX idx_dispute_source_biz (source, biz_no),
    INDEX idx_dispute_check (check_name, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dispute_fix_log (
    id            BIGINT        PRIMARY KEY,
    dispute_id    BIGINT        NOT NULL,
    action        VARCHAR(32)   NOT NULL,
    operator      VARCHAR(64),
    before_status VARCHAR(16),
    after_status  VARCHAR(16),
    detail        VARCHAR(1024),
    created_at    BIGINT        NOT NULL,
    INDEX idx_dispute_fix_log (dispute_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 幂等模板本地表（DISPUTE 登记 / DISPUTE_FIX 修复）
CREATE TABLE IF NOT EXISTS idempotent_record (
    biz_type   VARCHAR(32) NOT NULL,
    biz_no     VARCHAR(64) NOT NULL,
    result     LONGTEXT,
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (biz_type, biz_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- I-3 分布式调度 ShedLock 锁表（BIGINT 毫秒）。docker 现存库由 ShedLockSchemaInitializer 启动时幂等补建。
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until BIGINT       NOT NULL,
    locked_at  BIGINT       NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================================================================
-- P1-J 对账工作流（RC-2/RC-3/RC-4/RC-5）——全部 additive，新表全部不分片（SINGLE 规则钉主库）。
-- 现存库：recon_report 的 biz_date/recon_type/channel_code 列（含 recon_report_0..3 物理表）
-- 由 ReconSchemaUpgrade 启动时幂等补齐（与 AccountingSchemaUpgrade 同模式），绝不 DROP/DELETE。
-- ==================================================================
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_recon_batch', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_channel_recon', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_channel_diff', 0);

CREATE TABLE IF NOT EXISTS recon_batch (
    id            VARCHAR(32) PRIMARY KEY,
    biz_date      BIGINT       NOT NULL,
    recon_type    VARCHAR(16)  NOT NULL,
    channel_code  VARCHAR(32)  NOT NULL DEFAULT '',
    shard_index   INT          NOT NULL DEFAULT 0,
    shard_total   INT          NOT NULL DEFAULT 1,
    status        VARCHAR(16)  NOT NULL,
    attempt       INT          NOT NULL DEFAULT 1,
    report_id     VARCHAR(32),
    total         INT          NOT NULL DEFAULT 0,
    checked       INT          NOT NULL DEFAULT 0,
    passed        INT          NOT NULL DEFAULT 0,
    failed        INT          NOT NULL DEFAULT 0,
    dispute_count INT          NOT NULL DEFAULT 0,
    message       VARCHAR(512),
    created_at    BIGINT       NOT NULL,
    updated_at    BIGINT       NOT NULL,
    UNIQUE KEY uk_recon_batch (biz_date, recon_type, channel_code, shard_index, shard_total)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS recon_progress (
    biz_date      BIGINT       NOT NULL,
    recon_type    VARCHAR(16)  NOT NULL,
    shard_index   INT          NOT NULL DEFAULT 0,
    shard_total   INT          NOT NULL DEFAULT 1,
    total         BIGINT       NOT NULL DEFAULT 0,
    checked       BIGINT       NOT NULL DEFAULT 0,
    passed        INT          NOT NULL DEFAULT 0,
    failed        INT          NOT NULL DEFAULT 0,
    dispute_count BIGINT       NOT NULL DEFAULT 0,
    status        VARCHAR(16)  NOT NULL,
    updated_at    BIGINT       NOT NULL,
    PRIMARY KEY (biz_date, recon_type, shard_index, shard_total)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS channel_recon_run (
    id             VARCHAR(32) PRIMARY KEY,
    channel_code   VARCHAR(32)  NOT NULL,
    biz_date       BIGINT       NOT NULL,
    source         VARCHAR(16)  NOT NULL,
    bill_id        BIGINT,
    internal_count BIGINT       NOT NULL DEFAULT 0,
    external_count BIGINT       NOT NULL DEFAULT 0,
    matched_count  BIGINT       NOT NULL DEFAULT 0,
    long_count     BIGINT       NOT NULL DEFAULT 0,
    short_count    BIGINT       NOT NULL DEFAULT 0,
    amount_count   BIGINT       NOT NULL DEFAULT 0,
    status_count   BIGINT       NOT NULL DEFAULT 0,
    fee_count      BIGINT       NOT NULL DEFAULT 0,
    internal_total BIGINT       NOT NULL DEFAULT 0,
    external_total BIGINT       NOT NULL DEFAULT 0,
    net_diff       BIGINT       NOT NULL DEFAULT 0,
    pass           BOOLEAN      NOT NULL DEFAULT FALSE,
    dispute_count  BIGINT       NOT NULL DEFAULT 0,
    fee_note       VARCHAR(512),
    message        VARCHAR(512),
    created_at     BIGINT       NOT NULL,
    updated_at     BIGINT       NOT NULL,
    UNIQUE KEY uk_channel_recon_run (channel_code, biz_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS channel_recon_diff (
    id               BIGINT       PRIMARY KEY,
    channel_code     VARCHAR(32)  NOT NULL,
    biz_date         BIGINT       NOT NULL,
    biz_no           VARCHAR(64)  NOT NULL,
    channel_order_no VARCHAR(64),
    diff_type        VARCHAR(16)  NOT NULL,
    internal_amount  BIGINT,
    external_amount  BIGINT,
    internal_fee     BIGINT,
    external_fee     BIGINT,
    internal_status  VARCHAR(16),
    external_status  VARCHAR(16),
    status           VARCHAR(16)  NOT NULL,
    dispute_id       BIGINT,
    suggestion       VARCHAR(512),
    created_at       BIGINT       NOT NULL,
    updated_at       BIGINT       NOT NULL,
    UNIQUE KEY uk_channel_recon_diff (channel_code, biz_date, biz_no, diff_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
