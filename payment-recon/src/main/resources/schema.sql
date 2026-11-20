CREATE SEQUENCE IF NOT EXISTS seq_recon START WITH 1;
CREATE ALIAS IF NOT EXISTS next_value_for FOR "com.qoder.payment.common.db.H2Sequence.next";

CREATE TABLE IF NOT EXISTS recon_report (
    id         VARCHAR(32) PRIMARY KEY,
    checked_at BIGINT  NOT NULL,
    pass       BOOLEAN NOT NULL,
    report     CLOB    NOT NULL
);

-- X-1 差错处理平台：差错单 + 处理审计（S-4/RC-1/N-2 共用载体）
CREATE SEQUENCE IF NOT EXISTS seq_dispute START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_dispute_fix START WITH 1;

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
    CONSTRAINT uk_dispute_no UNIQUE (dispute_no)
);
CREATE INDEX IF NOT EXISTS idx_dispute_status ON dispute_order (status);
CREATE INDEX IF NOT EXISTS idx_dispute_source_biz ON dispute_order (source, biz_no);
CREATE INDEX IF NOT EXISTS idx_dispute_check ON dispute_order (check_name, status);

CREATE TABLE IF NOT EXISTS dispute_fix_log (
    id            BIGINT        PRIMARY KEY,
    dispute_id    BIGINT        NOT NULL,
    action        VARCHAR(32)   NOT NULL,
    operator      VARCHAR(64),
    before_status VARCHAR(16),
    after_status  VARCHAR(16),
    detail        VARCHAR(1024),
    created_at    BIGINT        NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_dispute_fix_log ON dispute_fix_log (dispute_id);

-- 幂等模板本地表（DISPUTE 登记 / DISPUTE_FIX 修复）
CREATE TABLE IF NOT EXISTS idempotent_record (
    biz_type   VARCHAR(32) NOT NULL,
    biz_no     VARCHAR(64) NOT NULL,
    result     CLOB,
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (biz_type, biz_no)
);
-- I-3 分布式调度 ShedLock 锁表（BIGINT 毫秒，与 MySQL 同构；分片对账锁名 reconRun:<idx>/<total>）。
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until BIGINT       NOT NULL,
    locked_at  BIGINT       NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

-- ==================================================================
-- P1-J 对账工作流（RC-2/RC-3/RC-4/RC-5）——全部 additive：只 CREATE IF NOT EXISTS /
-- ADD COLUMN IF NOT EXISTS，绝不 DROP/DELETE/改写既有行。新表全部不分片（SINGLE）。
-- ==================================================================
CREATE SEQUENCE IF NOT EXISTS seq_recon_batch START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_channel_recon START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_channel_diff START WITH 1;

-- RC-2 recon_report 按会计日落报告（additive 列；docker 分片物理表由 ReconSchemaUpgrade 补齐）
ALTER TABLE recon_report ADD COLUMN IF NOT EXISTS biz_date BIGINT;
ALTER TABLE recon_report ADD COLUMN IF NOT EXISTS recon_type VARCHAR(16);
ALTER TABLE recon_report ADD COLUMN IF NOT EXISTS channel_code VARCHAR(32);
CREATE INDEX IF NOT EXISTS idx_recon_report_day ON recon_report (biz_date);

-- RC-2/RC-3 对账批次：状态机 PENDING→RUNNING→SUCCESS/FAILED，同会计日+类型+渠道+分片唯一（重跑复用行）
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
    CONSTRAINT uk_recon_batch UNIQUE (biz_date, recon_type, channel_code, shard_index, shard_total)
);

-- RC-4/RC-5 分片进度（各片一行，Java 归并汇总）
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
);

-- RC-3 渠道内外对账运行汇总（重跑复用行）
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
    CONSTRAINT uk_channel_recon_run UNIQUE (channel_code, biz_date)
);

-- RC-3 逐笔差异（长款/短款/金额/状态/手续费，重跑去重）
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
    CONSTRAINT uk_channel_recon_diff UNIQUE (channel_code, biz_date, biz_no, diff_type)
);
