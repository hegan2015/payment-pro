CREATE SEQUENCE IF NOT EXISTS seq_account START WITH 1;
CREATE ALIAS IF NOT EXISTS next_value_for FOR "com.qoder.payment.common.db.H2Sequence.next";

CREATE TABLE IF NOT EXISTS account (
    id          VARCHAR(32) PRIMARY KEY,
    member_id   VARCHAR(32) NOT NULL,
    member_name VARCHAR(64),
    type        VARCHAR(16) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    created_at  BIGINT      NOT NULL,
    CONSTRAINT uk_account_member UNIQUE (member_id)
);

-- ---------- P1 C 泳道 additive（A-1/A-3/M-3）：列只增不删，既有行保留 ----------
ALTER TABLE account ADD COLUMN IF NOT EXISTS account_type VARCHAR(16) DEFAULT 'BALANCE';
ALTER TABLE account ADD COLUMN IF NOT EXISTS level VARCHAR(8) DEFAULT 'I';
ALTER TABLE account ADD COLUMN IF NOT EXISTS freeze_type VARCHAR(16) DEFAULT 'NONE';
ALTER TABLE account ADD COLUMN IF NOT EXISTS freeze_reason VARCHAR(256);
ALTER TABLE account ADD COLUMN IF NOT EXISTS freeze_at BIGINT;
-- ---------- P2 C 泳道 additive（A-4 多币种）：列只增不删，既有行保留 ----------
-- 既有账户默认 CNY，行为逐字节不变；只回填新增列（与 A-1 account_type 回填同一先例）。
-- 必须在唯一键迁移之前加列（约束含 currency）。
ALTER TABLE account ADD COLUMN IF NOT EXISTS currency VARCHAR(8) DEFAULT 'CNY';
-- A-1 约束迁移：一会员一账户 → 一会员一类型一户（计划 §3.I A-1 明确要求；不删任何行）
-- 新列回填（仅触碰新增列，既有业务列不改）：保证唯一约束对既有行生效
UPDATE account SET account_type = 'BALANCE' WHERE account_type IS NULL;
UPDATE account SET level = 'I' WHERE level IS NULL;
UPDATE account SET freeze_type = 'NONE' WHERE freeze_type IS NULL;
UPDATE account SET currency = 'CNY' WHERE currency IS NULL;
ALTER TABLE account DROP CONSTRAINT IF EXISTS uk_account_member;
-- A-4：一会员一类型一币种一户。存量行全部 CNY ⇒ 对既有 CNY 户的唯一性与 A-1
-- (member_id,account_type) 幂等语义**逐字节等价**，只是额外允许同会员同类型的外币户。
-- **顺序关键**：先建三列键再删旧两列键 —— 否则重复启动时旧两列键会在已有外币户后重建失败。
ALTER TABLE account ADD CONSTRAINT IF NOT EXISTS uk_account_member_type_ccy UNIQUE (member_id, account_type, currency);
ALTER TABLE account DROP CONSTRAINT IF EXISTS uk_account_member_type;

-- ---------- A-2 绑卡（卡号哈希+掩码，绝不落明文） ----------
CREATE SEQUENCE IF NOT EXISTS seq_card START WITH 1;
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
    CONSTRAINT uk_bank_card UNIQUE (account_id, card_no_hash)
);

-- ---------- A-3 冻结细分（金额冻结单 + 凭证留痕） ----------
CREATE SEQUENCE IF NOT EXISTS seq_freeze START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_freeze_log START WITH 1;
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
    CONSTRAINT uk_freeze_biz UNIQUE (biz_no)
);
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
    created_at    BIGINT      NOT NULL
);

-- ---------- M-3 分级限额规则（幂等种子见 AccountLevelService） ----------
CREATE TABLE IF NOT EXISTS account_level_rule (
    level         VARCHAR(8) PRIMARY KEY,
    single_limit  BIGINT NOT NULL,
    daily_limit   BIGINT NOT NULL,
    balance_limit BIGINT NOT NULL,
    require_kyc   VARCHAR(16) NOT NULL
);

-- ---------- P2 C 泳道（A-4 多币种）----------
-- currency 列与唯一键迁移已在上方 A-1 additive 块内完成（必须先加列再迁移约束，
-- 且先建三列键后删两列键，保证重复启动幂等）。此处不再重复 DDL。

-- ---------- P2 G 泳道 additive（A-5 开销户/升降级）----------
-- 只增列 + 只建新表；销户是逻辑状态（lifecycle_status=CLOSED 且 status=CLOSED），账户行永久保留。
ALTER TABLE account ADD COLUMN IF NOT EXISTS lifecycle_status VARCHAR(16) DEFAULT 'ACTIVE';
ALTER TABLE account ADD COLUMN IF NOT EXISTS closed_at BIGINT;
UPDATE account SET lifecycle_status = 'ACTIVE' WHERE lifecycle_status IS NULL;

CREATE SEQUENCE IF NOT EXISTS seq_account_lifecycle START WITH 1;
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
    created_at  BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_lifecycle_account ON account_lifecycle_log (account_id);