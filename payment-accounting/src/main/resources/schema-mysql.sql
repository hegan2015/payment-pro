-- payment-accounting · MySQL（pp_accounting）。对空库执行一次；docker profile sql.init.mode=never。
CREATE TABLE IF NOT EXISTS sequences (
    name  VARCHAR(64) PRIMARY KEY,
    value BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_entry', 0);
DROP FUNCTION IF EXISTS next_value_for;
DELIMITER //
CREATE FUNCTION next_value_for(s VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) RETURNS BIGINT
MODIFIES SQL DATA
BEGIN
    UPDATE sequences SET value = LAST_INSERT_ID(value + 1) WHERE name = s;
    RETURN LAST_INSERT_ID();
END//
DELIMITER ;

CREATE TABLE IF NOT EXISTS account_balance (
    account_id VARCHAR(32) PRIMARY KEY,
    balance    BIGINT NOT NULL DEFAULT 0,
    frozen     BIGINT NOT NULL DEFAULT 0,
    version    INT    NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ledger_entry (
    id            VARCHAR(32) PRIMARY KEY,
    seq           BIGINT       NOT NULL,
    biz_no        VARCHAR(64)  NOT NULL,
    account_id    VARCHAR(32)  NOT NULL,
    dir           VARCHAR(1)   NOT NULL,
    amount        BIGINT       NOT NULL,
    balance_after BIGINT       NOT NULL,
    subject       VARCHAR(128),
    ref_id        VARCHAR(64),
    created_at    BIGINT       NOT NULL,
    INDEX idx_entry_account (account_id, seq),
    INDEX idx_entry_biz (biz_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS transfer_record (
    biz_no       VARCHAR(64) PRIMARY KEY,
    `lines`      LONGTEXT     NOT NULL,
    subject      VARCHAR(128),
    ref_id       VARCHAR(64),
    reversed     BOOLEAN      NOT NULL DEFAULT FALSE,
    voucher_type VARCHAR(16),
    created_at   BIGINT       NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- S-6 凭证类型 NORMAL/REVERSE/SUSPENSE：新建库已含 voucher_type 列（上方 CREATE TABLE）。
-- docker 现存库不会重跑 init（sql.init.mode=never），需手工执行一次性 ALTER：
--   ALTER TABLE transfer_record ADD COLUMN voucher_type VARCHAR(16);
--   UPDATE transfer_record SET voucher_type='REVERSE' WHERE voucher_type IS NULL AND biz_no LIKE 'RV%';
--   UPDATE transfer_record SET voucher_type='NORMAL' WHERE voucher_type IS NULL;
-- （回填仅写 voucher_type 元数据列；lines 不可篡改，绝不 UPDATE）

CREATE TABLE IF NOT EXISTS tcc_branch_log (
    xid        VARCHAR(64) NOT NULL,
    branch_id  VARCHAR(80) NOT NULL,
    action     VARCHAR(32) NOT NULL,
    status     VARCHAR(16) NOT NULL,
    context    LONGTEXT,
    created_at BIGINT      NOT NULL,
    updated_at BIGINT      NOT NULL,
    PRIMARY KEY (xid, branch_id, action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS idempotent_record (
    biz_type   VARCHAR(32) NOT NULL,
    biz_no     VARCHAR(64) NOT NULL,
    result     LONGTEXT,
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (biz_type, biz_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- S-7 备付金账实核对。docker 现存库由 AccountingSchemaUpgrade 启动时幂等补建（只增不改）。
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_reserve', 0);
CREATE TABLE IF NOT EXISTS reserve_check_log (
    id             VARCHAR(32) PRIMARY KEY,
    book_balance   BIGINT  NOT NULL,
    actual_balance BIGINT  NOT NULL,
    diff           BIGINT  NOT NULL,
    pass           BOOLEAN NOT NULL,
    checked_at     BIGINT  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS reserve_bank_adjust (
    id         VARCHAR(32) PRIMARY KEY,
    amount     BIGINT NOT NULL,
    reason     VARCHAR(256),
    created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- I-5b 资金域分库分表（单库分表）：account_balance / ledger_entry 按 account_id 分 4 片。
-- 分片表 account_balance_0..3 / ledger_entry_0..3 由 ShardTableBootstrap 启动时
--   CREATE TABLE IF NOT EXISTS ... LIKE 原表 + 只 INSERT 复制建立（原表原行全部保留）；
--   手工/审计通道见 ops-pro/deploy/payment-sharding-fund.sql。
-- **不分片**：transfer_record（凭证只追加）/ tcc_branch_log（分支幂等）/ idempotent_record /
--   sequences / reserve_check_log / reserve_bank_adjust / shard_route_state。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS shard_route_state (
    domain     VARCHAR(64) NOT NULL PRIMARY KEY,
    active     BOOLEAN     NOT NULL,
    updated_at BIGINT      NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===========================================================================
-- P1 第二期 · E 泳道账务工作流（AC-2/AC-3/AC-4/AC-5）。全部 additive。
-- docker 现存库 sql.init.mode=never 不重跑本脚本：由 AccountingSchemaUpgrade 启动时
-- 幂等补建/加列（含 ledger_entry_0..3 物理分片表），无需手工 ALTER、不改 ops-pro。
-- 以下新表**全部不分片**（全局元数据/日历/派生汇总/作业留痕）。
-- ===========================================================================
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_subject', 0);

CREATE TABLE IF NOT EXISTS accounting_subject (
    subject_code   VARCHAR(32) PRIMARY KEY,
    name           VARCHAR(64) NOT NULL,
    type           VARCHAR(16) NOT NULL,
    direction      VARCHAR(1)  NOT NULL,
    parent_code    VARCHAR(32),
    subject_level  INT         NOT NULL DEFAULT 2,
    system_account VARCHAR(32),
    system_flag    BOOLEAN     NOT NULL DEFAULT FALSE,
    status         VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    created_at     BIGINT      NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS account_subject_map (
    account_id   VARCHAR(32) PRIMARY KEY,
    subject_code VARCHAR(32) NOT NULL,
    created_at   BIGINT      NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS subject_balance (
    subject_code    VARCHAR(32) NOT NULL,
    biz_date        BIGINT      NOT NULL,
    opening_balance BIGINT      NOT NULL DEFAULT 0,
    debit_total     BIGINT      NOT NULL DEFAULT 0,
    credit_total    BIGINT      NOT NULL DEFAULT 0,
    balance         BIGINT      NOT NULL DEFAULT 0,
    entry_count     INT         NOT NULL DEFAULT 0,
    account_count   INT         NOT NULL DEFAULT 0,
    updated_at      BIGINT      NOT NULL,
    PRIMARY KEY (subject_code, biz_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS accounting_day (
    biz_date              BIGINT PRIMARY KEY,
    status                VARCHAR(16) NOT NULL,
    opened_at             BIGINT,
    cut_at                BIGINT,
    closed_at             BIGINT,
    prev_balance_snapshot LONGTEXT,
    balance_sum           BIGINT,
    total_debit           BIGINT,
    total_credit          BIGINT,
    accounts              INT,
    entries               INT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS day_end_report (
    biz_date           BIGINT PRIMARY KEY,
    income_total       BIGINT      NOT NULL DEFAULT 0,
    expense_total      BIGINT      NOT NULL DEFAULT 0,
    profit             BIGINT      NOT NULL DEFAULT 0,
    transferred        BOOLEAN     NOT NULL DEFAULT FALSE,
    transfer_biz_no    VARCHAR(64),
    debit_total        BIGINT      NOT NULL DEFAULT 0,
    credit_total       BIGINT      NOT NULL DEFAULT 0,
    balance_sum        BIGINT      NOT NULL DEFAULT 0,
    subject_count      INT         NOT NULL DEFAULT 0,
    entry_count        INT         NOT NULL DEFAULT 0,
    legacy_entry_count INT         NOT NULL DEFAULT 0,
    status             VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    updated_at         BIGINT      NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS accounting_batch_run (
    id          VARCHAR(32) PRIMARY KEY,
    batch_type  VARCHAR(32) NOT NULL,
    biz_date    BIGINT      NOT NULL,
    shard_index INT         NOT NULL DEFAULT 0,
    shard_total INT         NOT NULL DEFAULT 1,
    status      VARCHAR(16) NOT NULL,
    step        VARCHAR(32),
    attempt     INT         NOT NULL DEFAULT 1,
    error       VARCHAR(512),
    started_at  BIGINT      NOT NULL,
    finished_at BIGINT,
    CONSTRAINT uk_batch_run UNIQUE (batch_type, biz_date, shard_index, shard_total)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS accounting_day_snapshot (
    biz_no     VARCHAR(64) PRIMARY KEY,
    biz_date   BIGINT NOT NULL,
    created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until BIGINT       NOT NULL,
    locked_at  BIGINT       NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- additive 列（新建库直接含列；现存库由 AccountingSchemaUpgrade 补，含各物理分片表）：
--   ALTER TABLE ledger_entry ADD COLUMN subject_code VARCHAR(32);
--   ALTER TABLE ledger_entry ADD COLUMN biz_date BIGINT;
--   ALTER TABLE ledger_entry_0..3 ADD COLUMN subject_code VARCHAR(32), ADD COLUMN biz_date BIGINT;
--   ALTER TABLE transfer_record ADD COLUMN biz_date BIGINT;
--   ALTER TABLE reserve_check_log ADD COLUMN biz_date BIGINT, ADD COLUMN channel_code VARCHAR(32),
--     ADD COLUMN level VARCHAR(16), ADD COLUMN direction VARCHAR(16), ADD COLUMN dispute_no VARCHAR(64);
--   ALTER TABLE reserve_bank_adjust ADD COLUMN channel_code VARCHAR(32);
-- （只加列，绝不 UPDATE/DELETE 既有行；ledger_entry.lines 与 transfer_record.lines 不可篡改）
--
-- **不分片新增表**（须列入 payment.sharding.single-tables）：
--   accounting_subject / account_subject_map / subject_balance / accounting_day /
--   day_end_report / accounting_batch_run / accounting_day_snapshot / shedlock

-- ===========================================================================
-- P2 第三期 · C 泳道（A-4 多币种+汇率 / AC-6 多币种账务）
-- docker sql.init.mode=never ⇒ 现存库由 AccountingSchemaUpgrade 幂等执行同一套 DDL
-- （含 account_balance_0..3 / ledger_entry_0..3 物理分片表补列）；此处供全新库一次性建表。
-- 只 CREATE / ADD COLUMN，绝不 DROP TABLE / TRUNCATE / DELETE / UPDATE 既有行。
-- ===========================================================================
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_fx_rate', 0);

CREATE TABLE IF NOT EXISTS fx_rate (
    id            VARCHAR(32) PRIMARY KEY,
    from_currency VARCHAR(8)  NOT NULL,
    to_currency   VARCHAR(8)  NOT NULL,
    buy_rate      BIGINT      NOT NULL,
    sell_rate     BIGINT      NOT NULL,
    mid_rate      BIGINT      NOT NULL,
    rate_scale    INT         NOT NULL DEFAULT 10000,
    version       BIGINT      NOT NULL,
    effective_at  BIGINT      NOT NULL,
    biz_date      BIGINT,
    source        VARCHAR(32),
    operator      VARCHAR(64),
    created_at    BIGINT      NOT NULL,
    UNIQUE KEY uk_fx_rate_version (from_currency, to_currency, version),
    KEY idx_fx_rate_pair (from_currency, to_currency, effective_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS fx_exchange (
    biz_no            VARCHAR(64) PRIMARY KEY,
    from_biz_no       VARCHAR(64) NOT NULL,
    to_biz_no         VARCHAR(64) NOT NULL,
    from_account_id   VARCHAR(32) NOT NULL,
    to_account_id     VARCHAR(32) NOT NULL,
    from_currency     VARCHAR(8)  NOT NULL,
    to_currency       VARCHAR(8)  NOT NULL,
    amount            BIGINT      NOT NULL,
    fee               BIGINT      NOT NULL DEFAULT 0,
    fee_bps           INT         NOT NULL DEFAULT 0,
    gross_to_amount   BIGINT      NOT NULL,
    net_to_amount     BIGINT      NOT NULL,
    fx_gain           BIGINT      NOT NULL DEFAULT 0,
    fx_gain_account   VARCHAR(32),
    fee_account       VARCHAR(32),
    buy_rate          BIGINT      NOT NULL,
    sell_rate         BIGINT      NOT NULL,
    mid_rate          BIGINT      NOT NULL,
    rate_scale        INT         NOT NULL,
    rate_version      BIGINT      NOT NULL,
    rate_source       VARCHAR(32),
    rate_effective_at BIGINT,
    from_balance_after BIGINT,
    to_balance_after   BIGINT,
    operator          VARCHAR(64),
    created_at        BIGINT      NOT NULL,
    KEY idx_fx_exchange_from (from_account_id),
    KEY idx_fx_exchange_to (to_account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS accounting_currency (
    currency   VARCHAR(8) PRIMARY KEY,
    base_flag  TINYINT(1)  NOT NULL DEFAULT 0,
    created_at BIGINT      NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- additive 列（现存库由 AccountingSchemaUpgrade 补，含各物理分片表）：
--   ALTER TABLE account_balance ADD COLUMN currency VARCHAR(8);
--   ALTER TABLE account_balance_0..3 ADD COLUMN currency VARCHAR(8);
--   ALTER TABLE ledger_entry ADD COLUMN currency VARCHAR(8), ADD COLUMN exchange_rate BIGINT,
--     ADD COLUMN rate_scale INT, ADD COLUMN base_amount BIGINT;
--   ALTER TABLE ledger_entry_0..3 ADD COLUMN currency/exchange_rate/rate_scale/base_amount（同上）;
--   ALTER TABLE transfer_record ADD COLUMN currency/exchange_rate/rate_scale/base_amount（同上）;
-- **主键与分片键不变**：account_balance PK 仍 account_id、分片键仍 account_id（每账户单币种）。
-- **不分片新增表**（须列入 payment.sharding.single-tables）：fx_rate / fx_exchange / accounting_currency
