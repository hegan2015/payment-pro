-- payment-risk · R-5 设备指纹/IP 画像 + R-6 AML 增量 DDL（local H2，data/risk）。
-- 由 RiskPlusSchemaInitializer 在 Bean 阶段执行（早于 DeviceStore/AmlStore），双 profile 同构。
-- 只 CREATE IF NOT EXISTS，additive；绝不 DROP/TRUNCATE/DELETE 既有行。
CREATE SEQUENCE IF NOT EXISTS seq_device_rel START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_behavior START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_aml_rule START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_aml_report START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_aml_txn START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_aml_ref START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_aml_screen START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_aml_watch START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_aml_audit START WITH 1;

-- R-5 设备指纹（device_id 为脱敏存储键；原始设备标识永不落库）
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
    tags             CLOB,
    detail           VARCHAR(512),
    retention_until  BIGINT,
    expired          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       BIGINT       NOT NULL,
    updated_at       BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_device_last_seen ON device_fingerprint (last_seen);

-- R-5 设备-账户关联图谱（双向可查：同设备多账户 / 同账户多设备）
CREATE TABLE IF NOT EXISTS device_account_rel (
    id         VARCHAR(32) PRIMARY KEY,
    device_id  VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    first_seen BIGINT      NOT NULL,
    last_seen  BIGINT      NOT NULL,
    txn_count  INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_device_account UNIQUE (device_id, account_id)
);
CREATE INDEX IF NOT EXISTS idx_device_rel_account ON device_account_rel (account_id, last_seen);

-- R-5 IP 画像（离线沙箱数据源 + 本域历史拒绝聚合）
CREATE TABLE IF NOT EXISTS ip_profile (
    ip              VARCHAR(64) PRIMARY KEY,
    region          VARCHAR(64),
    country         VARCHAR(16),
    is_proxy        BOOLEAN     NOT NULL DEFAULT FALSE,
    is_idc          BOOLEAN     NOT NULL DEFAULT FALSE,
    account_count   INT         NOT NULL DEFAULT 0,
    reject_count    INT         NOT NULL DEFAULT 0,
    risk_score      INT         NOT NULL DEFAULT 0,
    tags            CLOB,
    detail          VARCHAR(512),
    source          VARCHAR(16),
    first_seen      BIGINT      NOT NULL,
    last_seen       BIGINT      NOT NULL,
    retention_until BIGINT,
    expired         BOOLEAN     NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_ip_last_seen ON ip_profile (last_seen);

-- R-5 行为序列（支付行为时序样本）
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
    expired    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_behavior_payer ON behavior_sequence (payer_id, created_at);
CREATE INDEX IF NOT EXISTS idx_behavior_device ON behavior_sequence (device_id, created_at);
CREATE INDEX IF NOT EXISTS idx_behavior_ip ON behavior_sequence (ip, created_at);

-- R-6 AML 规则（内置类型 + 可选 QLExpress 表达式，复用既有 RiskRuleEngine 护栏）
CREATE TABLE IF NOT EXISTS aml_rule (
    id             VARCHAR(32) PRIMARY KEY,
    name           VARCHAR(128),
    rule_type      VARCHAR(32)  NOT NULL,
    condition_expr CLOB,
    threshold      BIGINT,
    window_millis  BIGINT,
    min_count      INT,
    ratio          INT,
    score          INT          NOT NULL DEFAULT 0,
    hard_rule      BOOLEAN      NOT NULL DEFAULT FALSE,
    realtime       BOOLEAN      NOT NULL DEFAULT FALSE,
    status         VARCHAR(16)  NOT NULL,
    version        INT          NOT NULL DEFAULT 1,
    operator       VARCHAR(64),
    compile_status VARCHAR(16),
    compile_error  VARCHAR(512),
    created_at     BIGINT       NOT NULL,
    updated_at     BIGINT       NOT NULL
);

-- R-6 可疑报告 STR（幂等键 payer+rule+bizDate；状态机 PENDING→SUBMITTED→CLOSED）
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
    hard_rule       BOOLEAN      NOT NULL DEFAULT FALSE,
    detail          CLOB,
    dispute_id      BIGINT,
    operator        VARCHAR(64),
    submit_note     VARCHAR(512),
    submitted_at    BIGINT,
    closed_at       BIGINT,
    retention_until BIGINT,
    created_at      BIGINT       NOT NULL,
    updated_at      BIGINT       NOT NULL,
    CONSTRAINT uk_aml_report_idem UNIQUE (payer_id, rule_id, biz_date),
    CONSTRAINT uk_aml_report_no UNIQUE (report_no)
);
CREATE INDEX IF NOT EXISTS idx_aml_report_status ON aml_report (status, biz_date);
CREATE INDEX IF NOT EXISTS idx_aml_report_payer ON aml_report (payer_id, created_at);

-- R-6 AML 交易流水镜像（报告引用与流水一致可核对；只 INSERT）
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
    created_at  BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_aml_txn_payer ON aml_txn (payer_id, biz_date, created_at);
CREATE INDEX IF NOT EXISTS idx_aml_txn_date ON aml_txn (biz_date, created_at);

-- R-6 报告-交易引用
CREATE TABLE IF NOT EXISTS aml_txn_ref (
    id         VARCHAR(32) PRIMARY KEY,
    report_id  VARCHAR(32) NOT NULL,
    payment_id VARCHAR(64) NOT NULL,
    amount     BIGINT,
    direction  VARCHAR(8),
    created_at BIGINT      NOT NULL,
    CONSTRAINT uk_aml_ref UNIQUE (report_id, payment_id)
);

-- R-6 名单筛查留痕（命中与否都留痕）
CREATE TABLE IF NOT EXISTS aml_screening (
    id               VARCHAR(32) PRIMARY KEY,
    subject_type     VARCHAR(16),
    subject_mask     VARCHAR(128),
    list_type        VARCHAR(16),
    hit              BOOLEAN      NOT NULL DEFAULT FALSE,
    matched_entry_id VARCHAR(32),
    match_type       VARCHAR(16),
    report_id        BIGINT,
    trace_id         VARCHAR(64),
    created_at       BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_aml_screen_subject ON aml_screening (subject_mask, created_at);

-- R-6 沙箱名单（制裁 SANCTION / 政要 PEP；软删 status=REMOVED，历史行保留）
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
    CONSTRAINT uk_aml_watch UNIQUE (list_type, name)
);

-- R-6 状态流转审计（可追溯）
CREATE TABLE IF NOT EXISTS aml_audit (
    id          VARCHAR(32) PRIMARY KEY,
    report_id   VARCHAR(32) NOT NULL,
    action      VARCHAR(32) NOT NULL,
    from_status VARCHAR(16),
    to_status   VARCHAR(16),
    operator    VARCHAR(64),
    note        VARCHAR(512),
    created_at  BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_aml_audit_report ON aml_audit (report_id, created_at);