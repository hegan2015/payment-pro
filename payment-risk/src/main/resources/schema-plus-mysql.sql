-- payment-risk · R-5 设备指纹/IP 画像 + R-6 AML 增量 DDL（docker MySQL pp_risk）。
-- 由 RiskPlusSchemaInitializer 在 Bean 阶段执行（sql.init.mode=never 下自动补建，无需手工 ALTER、不改 ops-pro）。
-- 只 CREATE TABLE IF NOT EXISTS / INSERT IGNORE，additive；绝不 DROP/TRUNCATE/DELETE 既有行。
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_device_rel', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_behavior', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_aml_rule', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_aml_report', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_aml_txn', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_aml_ref', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_aml_screen', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_aml_watch', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_aml_audit', 0);

CREATE TABLE IF NOT EXISTS device_fingerprint (
    device_id        VARCHAR(64) PRIMARY KEY,
    device_mask      VARCHAR(64),
    fingerprint_hash VARCHAR(64),
    stability        VARCHAR(16),
    drift_count      INT          NOT NULL DEFAULT 0,
    first_seen       BIGINT       NOT NULL,
    last_seen        BIGINT       NOT NULL,
    account_count    INT          NOT NULL DEFAULT 0,
    risk_score       INT          NOT NULL DEFAULT 0,
    tags             LONGTEXT,
    detail           VARCHAR(512),
    retention_until  BIGINT,
    expired          TINYINT(1)   NOT NULL DEFAULT 0,
    created_at       BIGINT       NOT NULL,
    updated_at       BIGINT       NOT NULL,
    INDEX idx_device_last_seen (last_seen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS device_account_rel (
    id         VARCHAR(32) PRIMARY KEY,
    device_id  VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    first_seen BIGINT      NOT NULL,
    last_seen  BIGINT      NOT NULL,
    txn_count  INT         NOT NULL DEFAULT 0,
    UNIQUE KEY uk_device_account (device_id, account_id),
    INDEX idx_device_rel_account (account_id, last_seen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ip_profile (
    ip              VARCHAR(64) PRIMARY KEY,
    region          VARCHAR(64),
    country         VARCHAR(16),
    is_proxy        TINYINT(1)   NOT NULL DEFAULT 0,
    is_idc          TINYINT(1)   NOT NULL DEFAULT 0,
    account_count   INT          NOT NULL DEFAULT 0,
    reject_count    INT          NOT NULL DEFAULT 0,
    risk_score      INT          NOT NULL DEFAULT 0,
    tags            LONGTEXT,
    detail          VARCHAR(512),
    source          VARCHAR(16),
    first_seen      BIGINT       NOT NULL,
    last_seen       BIGINT       NOT NULL,
    retention_until BIGINT,
    expired         TINYINT(1)   NOT NULL DEFAULT 0,
    INDEX idx_ip_last_seen (last_seen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS behavior_sequence (
    id         VARCHAR(32) PRIMARY KEY,
    payer_id   VARCHAR(64),
    device_id  VARCHAR(64),
    ip         VARCHAR(64),
    region     VARCHAR(64),
    country    VARCHAR(16),
    action     VARCHAR(16),
    amount     BIGINT,
    trace_id   VARCHAR(64),
    expired    TINYINT(1)   NOT NULL DEFAULT 0,
    created_at BIGINT       NOT NULL,
    INDEX idx_behavior_payer (payer_id, created_at),
    INDEX idx_behavior_device (device_id, created_at),
    INDEX idx_behavior_ip (ip, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS aml_rule (
    id             VARCHAR(32) PRIMARY KEY,
    name           VARCHAR(128),
    rule_type      VARCHAR(32)  NOT NULL,
    condition_expr LONGTEXT,
    threshold      BIGINT,
    window_millis  BIGINT,
    min_count      INT,
    ratio          INT,
    score          INT          NOT NULL DEFAULT 0,
    hard_rule      TINYINT(1)   NOT NULL DEFAULT 0,
    realtime       TINYINT(1)   NOT NULL DEFAULT 0,
    status         VARCHAR(16)  NOT NULL,
    version        INT          NOT NULL DEFAULT 1,
    operator       VARCHAR(64),
    compile_status VARCHAR(16),
    compile_error  VARCHAR(512),
    created_at     BIGINT       NOT NULL,
    updated_at     BIGINT       NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS aml_report (
    id              VARCHAR(32) PRIMARY KEY,
    report_no       VARCHAR(64)  NOT NULL,
    payer_id        VARCHAR(64)  NOT NULL,
    merchant_id     VARCHAR(64),
    rule_id         VARCHAR(32)  NOT NULL,
    rule_type       VARCHAR(32),
    txn_amount      BIGINT,
    txn_count       INT,
    biz_date        VARCHAR(16)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    score           INT,
    disposition     VARCHAR(16),
    hard_rule       TINYINT(1)   NOT NULL DEFAULT 0,
    detail          LONGTEXT,
    dispute_id      BIGINT,
    operator        VARCHAR(64),
    submit_note     VARCHAR(512),
    submitted_at    BIGINT,
    closed_at       BIGINT,
    retention_until BIGINT,
    created_at      BIGINT       NOT NULL,
    updated_at      BIGINT       NOT NULL,
    UNIQUE KEY uk_aml_report_idem (payer_id, rule_id, biz_date),
    UNIQUE KEY uk_aml_report_no (report_no),
    INDEX idx_aml_report_status (status, biz_date),
    INDEX idx_aml_report_payer (payer_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS aml_txn (
    id          VARCHAR(32) PRIMARY KEY,
    payer_id    VARCHAR(64)  NOT NULL,
    merchant_id VARCHAR(64),
    payment_id  VARCHAR(64),
    amount      BIGINT       NOT NULL,
    direction   VARCHAR(8),
    scene       VARCHAR(16),
    method      VARCHAR(16),
    ip          VARCHAR(64),
    region      VARCHAR(64),
    country     VARCHAR(16),
    device_id   VARCHAR(64),
    hour_of_day INT,
    biz_date    VARCHAR(16)  NOT NULL,
    trace_id    VARCHAR(64),
    created_at  BIGINT       NOT NULL,
    INDEX idx_aml_txn_payer (payer_id, biz_date, created_at),
    INDEX idx_aml_txn_date (biz_date, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS aml_txn_ref (
    id         VARCHAR(32) PRIMARY KEY,
    report_id  VARCHAR(32) NOT NULL,
    payment_id VARCHAR(64) NOT NULL,
    amount     BIGINT,
    direction  VARCHAR(8),
    created_at BIGINT      NOT NULL,
    UNIQUE KEY uk_aml_ref (report_id, payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS aml_screening (
    id               VARCHAR(32) PRIMARY KEY,
    subject_type     VARCHAR(16),
    subject_mask     VARCHAR(128),
    list_type        VARCHAR(16),
    hit              TINYINT(1)   NOT NULL DEFAULT 0,
    matched_entry_id VARCHAR(32),
    match_type       VARCHAR(16),
    report_id        BIGINT,
    trace_id         VARCHAR(64),
    created_at       BIGINT       NOT NULL,
    INDEX idx_aml_screen_subject (subject_mask, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS aml_watchlist (
    id         VARCHAR(32) PRIMARY KEY,
    list_type  VARCHAR(16)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    aliases    VARCHAR(512),
    country    VARCHAR(16),
    status     VARCHAR(16)  NOT NULL,
    source     VARCHAR(32),
    operator   VARCHAR(64),
    created_at BIGINT       NOT NULL,
    updated_at BIGINT       NOT NULL,
    UNIQUE KEY uk_aml_watch (list_type, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS aml_audit (
    id          VARCHAR(32) PRIMARY KEY,
    report_id   VARCHAR(32) NOT NULL,
    action      VARCHAR(32) NOT NULL,
    from_status VARCHAR(16),
    to_status   VARCHAR(16),
    operator    VARCHAR(64),
    note        VARCHAR(512),
    created_at  BIGINT       NOT NULL,
    INDEX idx_aml_audit_report (report_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;