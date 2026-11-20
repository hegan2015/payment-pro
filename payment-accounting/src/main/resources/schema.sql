CREATE SEQUENCE IF NOT EXISTS seq_entry START WITH 1;
CREATE ALIAS IF NOT EXISTS next_value_for FOR "com.qoder.payment.common.db.H2Sequence.next";

CREATE TABLE IF NOT EXISTS account_balance (
    account_id VARCHAR(32) PRIMARY KEY,
    balance    BIGINT NOT NULL DEFAULT 0,
    frozen     BIGINT NOT NULL DEFAULT 0,
    version    INT    NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL
);

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
    created_at    BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_entry_account ON ledger_entry (account_id, seq);
CREATE INDEX IF NOT EXISTS idx_entry_biz ON ledger_entry (biz_no);

CREATE TABLE IF NOT EXISTS transfer_record (
    biz_no     VARCHAR(64) PRIMARY KEY,
    lines      CLOB         NOT NULL,
    subject    VARCHAR(128),
    ref_id     VARCHAR(64),
    reversed   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at BIGINT       NOT NULL
);
-- S-6 凭证类型 NORMAL/REVERSE/SUSPENSE：存量 H2 库自动加列（每次启动幂等执行）
ALTER TABLE transfer_record ADD COLUMN IF NOT EXISTS voucher_type VARCHAR(16);
-- 一次性回填（IS NULL 守卫，仅写 voucher_type 元数据列；lines 不可篡改，绝不 UPDATE）
UPDATE transfer_record SET voucher_type = 'REVERSE' WHERE voucher_type IS NULL AND biz_no LIKE 'RV%';
UPDATE transfer_record SET voucher_type = 'NORMAL' WHERE voucher_type IS NULL;

CREATE TABLE IF NOT EXISTS tcc_branch_log (
    xid        VARCHAR(64) NOT NULL,
    branch_id  VARCHAR(80) NOT NULL,
    action     VARCHAR(32) NOT NULL,
    status     VARCHAR(16) NOT NULL,
    context    CLOB,
    created_at BIGINT      NOT NULL,
    updated_at BIGINT      NOT NULL,
    PRIMARY KEY (xid, branch_id, action)
);

CREATE TABLE IF NOT EXISTS idempotent_record (
    biz_type   VARCHAR(32) NOT NULL,
    biz_no     VARCHAR(64) NOT NULL,
    result     CLOB,
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (biz_type, biz_no)
);
-- S-7 备付金账实核对（资金防线）：核对留痕 + 模拟银行存管侧独立事件（只追加）。
CREATE SEQUENCE IF NOT EXISTS seq_reserve START WITH 1;
CREATE TABLE IF NOT EXISTS reserve_check_log (
    id             VARCHAR(32) PRIMARY KEY,
    book_balance   BIGINT  NOT NULL,
    actual_balance BIGINT  NOT NULL,
    diff           BIGINT  NOT NULL,
    pass           BOOLEAN NOT NULL,
    checked_at     BIGINT  NOT NULL
);
CREATE TABLE IF NOT EXISTS reserve_bank_adjust (
    id         VARCHAR(32) PRIMARY KEY,
    amount     BIGINT NOT NULL,
    reason     VARCHAR(256),
    created_at BIGINT NOT NULL
);

-- I-5b 分片路由状态（单库不分片）：切流/回滚跨重启不丢权威侧。local 不写不读。
CREATE TABLE IF NOT EXISTS shard_route_state (
    domain     VARCHAR(64) NOT NULL PRIMARY KEY,
    active     BOOLEAN     NOT NULL,
    updated_at BIGINT      NOT NULL
);

-- ===========================================================================
-- P1 第二期 · E 泳道账务工作流（AC-2 科目 / AC-3 日切日终 / AC-4 账簿 / AC-5 账实）
-- 全部 additive：只 CREATE IF NOT EXISTS + ADD COLUMN IF NOT EXISTS，绝不 DROP/DELETE/改写既有行。
-- 以下新表**全部不分片**（全局元数据/日历/派生汇总/作业留痕）。
-- ===========================================================================
CREATE SEQUENCE IF NOT EXISTS seq_subject START WITH 1;

-- AC-2 科目目录（资产/负债/共同/权益/损益；level 1=类别根 2=明细科目）
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
);
-- AC-2 账户→科目映射（系统户固定映射；客户账户默认 2001 客户余额）
CREATE TABLE IF NOT EXISTS account_subject_map (
    account_id   VARCHAR(32) PRIMARY KEY,
    subject_code VARCHAR(32) NOT NULL,
    created_at   BIGINT      NOT NULL
);
-- AC-2/AC-4 科目余额表（科目+会计日；派生行，日终批幂等 upsert，绝不 DELETE）
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
);
-- AC-3 会计日（OPEN→CUTTING→CLOSED；日切只切标签不动余额）
CREATE TABLE IF NOT EXISTS accounting_day (
    biz_date              BIGINT PRIMARY KEY,
    status                VARCHAR(16) NOT NULL,
    opened_at             BIGINT,
    cut_at                BIGINT,
    closed_at             BIGINT,
    prev_balance_snapshot CLOB,
    balance_sum           BIGINT,
    total_debit           BIGINT,
    total_credit          BIGINT,
    accounts              INT,
    entries               INT
);
-- AC-3 日终报告（损益汇总 + 结转状态）
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
);
-- AC-3 批处理状态机留痕（RUNNING/SUCCESS/FAILED + attempt；失败重跑只 UPDATE 本行）
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
);
-- AC-3 在途 TCC 受理日快照（Try 落库、Confirm 按此归属凭证；只追加）
CREATE TABLE IF NOT EXISTS accounting_day_snapshot (
    biz_no     VARCHAR(64) PRIMARY KEY,
    biz_date   BIGINT NOT NULL,
    created_at BIGINT NOT NULL
);

-- AC-2/AC-3 分录与凭证 additive 列（存量行为 NULL，只读不回填；lines 绝不 UPDATE）
ALTER TABLE ledger_entry ADD COLUMN IF NOT EXISTS subject_code VARCHAR(32);
ALTER TABLE ledger_entry ADD COLUMN IF NOT EXISTS biz_date BIGINT;
CREATE INDEX IF NOT EXISTS idx_entry_day ON ledger_entry (biz_date);
CREATE INDEX IF NOT EXISTS idx_entry_subject ON ledger_entry (subject_code);
ALTER TABLE transfer_record ADD COLUMN IF NOT EXISTS biz_date BIGINT;

-- AC-5 账实核对新维度（additive；P0 book/actual/diff/pass 口径不变）
ALTER TABLE reserve_check_log ADD COLUMN IF NOT EXISTS biz_date BIGINT;
ALTER TABLE reserve_check_log ADD COLUMN IF NOT EXISTS channel_code VARCHAR(32);
ALTER TABLE reserve_check_log ADD COLUMN IF NOT EXISTS level VARCHAR(16);
ALTER TABLE reserve_check_log ADD COLUMN IF NOT EXISTS direction VARCHAR(16);
ALTER TABLE reserve_check_log ADD COLUMN IF NOT EXISTS dispute_no VARCHAR(64);
ALTER TABLE reserve_bank_adjust ADD COLUMN IF NOT EXISTS channel_code VARCHAR(32);

-- I-3 调度锁表（本域新增 JobHandler 后由 ShedLockSchemaInitializer 幂等补建，此处同构声明）
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until BIGINT       NOT NULL,
    locked_at  BIGINT       NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

-- ===========================================================================
-- P2 第三期 · C 泳道（A-4 多币种+汇率 / AC-6 多币种账务）
-- 全部 additive：只 CREATE IF NOT EXISTS + ADD COLUMN IF NOT EXISTS，绝不 DROP/DELETE/改写既有行。
-- 新表 fx_rate / fx_exchange / accounting_currency **全部不分片**（全局元数据/幂等单据）。
-- account_balance 主键仍 account_id（每账户单币种）⇒ 分片键不变、同账户同片。
-- 存量行新列一律 NULL，CNY 账套按 NULL 读 ⇒ 既有单币种口径逐字节不变。
-- ===========================================================================
CREATE SEQUENCE IF NOT EXISTS seq_fx_rate START WITH 1;

-- A-4 汇率版本（买入/卖出/中间价，long+scale；只追加，历史版本永不改写）
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
    CONSTRAINT uk_fx_rate_version UNIQUE (from_currency, to_currency, version)
);
CREATE INDEX IF NOT EXISTS idx_fx_rate_pair ON fx_rate (from_currency, to_currency, effective_at);

-- A-4 换汇单（幂等单据 + 汇率快照落库；两张同币种凭证号确定性派生）
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
    created_at        BIGINT      NOT NULL
);

-- AC-6 币种账套目录（trial 逐币种独立试算的依据；只 INSERT，绝不 DELETE）
CREATE TABLE IF NOT EXISTS accounting_currency (
    currency   VARCHAR(8) PRIMARY KEY,
    base_flag  BOOLEAN    NOT NULL DEFAULT FALSE,
    created_at BIGINT     NOT NULL
);

-- AC-6 余额/分录/凭证 additive 列（存量行为 NULL，只读不回填；lines 绝不 UPDATE）
ALTER TABLE account_balance ADD COLUMN IF NOT EXISTS currency VARCHAR(8);
ALTER TABLE ledger_entry ADD COLUMN IF NOT EXISTS currency VARCHAR(8);
ALTER TABLE ledger_entry ADD COLUMN IF NOT EXISTS exchange_rate BIGINT;
ALTER TABLE ledger_entry ADD COLUMN IF NOT EXISTS rate_scale INT;
ALTER TABLE ledger_entry ADD COLUMN IF NOT EXISTS base_amount BIGINT;
CREATE INDEX IF NOT EXISTS idx_entry_currency ON ledger_entry (currency);
ALTER TABLE transfer_record ADD COLUMN IF NOT EXISTS currency VARCHAR(8);
ALTER TABLE transfer_record ADD COLUMN IF NOT EXISTS exchange_rate BIGINT;
ALTER TABLE transfer_record ADD COLUMN IF NOT EXISTS rate_scale INT;
ALTER TABLE transfer_record ADD COLUMN IF NOT EXISTS base_amount BIGINT;
