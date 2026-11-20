-- payment-trade · MySQL（pp_trade）。对空库执行一次；docker profile sql.init.mode=never。
CREATE TABLE IF NOT EXISTS sequences (
    name  VARCHAR(64) PRIMARY KEY,
    value BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_order', 0);
DROP FUNCTION IF EXISTS next_value_for;
DELIMITER //
CREATE FUNCTION next_value_for(s VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) RETURNS BIGINT
MODIFIES SQL DATA
BEGIN
    UPDATE sequences SET value = LAST_INSERT_ID(value + 1) WHERE name = s;
    RETURN LAST_INSERT_ID();
END//
DELIMITER ;

CREATE TABLE IF NOT EXISTS trade_order (
    id              VARCHAR(32) PRIMARY KEY,
    merchant_id     VARCHAR(32)  NOT NULL,
    merchant_name   VARCHAR(64),
    subject         VARCHAR(128) NOT NULL,
    amount          BIGINT       NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    payment_id      VARCHAR(32),
    refunded_amount BIGINT       NOT NULL DEFAULT 0,
    out_trade_no    VARCHAR(64),
    biz_type        VARCHAR(16)  NOT NULL DEFAULT 'TRADE',
    created_at      BIGINT       NOT NULL,
    paid_at         BIGINT,
    closed_at       BIGINT,
    -- P1 · G 泳道 T-3/T-1/T-4 additive 列（存量库由 TradeSchemaUpgrade 同步补到原单表与 trade_order_0..3）
    product_type      VARCHAR(16) NOT NULL DEFAULT 'INSTANT',
    payee_merchant_id VARCHAR(32),
    escrow_status     VARCHAR(16),
    expire_at         BIGINT,
    INDEX idx_order_merchant (merchant_id),
    INDEX idx_order_expire (status, expire_at),
    UNIQUE INDEX idx_order_out_trade (merchant_id, out_trade_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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

-- ---------------------------------------------------------------------------
-- I-5b 资金域分库分表（单库分表）：trade_order 按 merchant_id 分 4 片。
-- 幂等唯一键 (merchant_id, out_trade_no) 的首列即分片键 ⇒ 同键必落同片，
--   片内唯一键等价全局唯一键（§0.7 trade 幂等语义不变，无需额外索引表）。
-- 分片表 trade_order_0..3 由 ShardTableBootstrap 启动时 CREATE IF NOT EXISTS ... LIKE + 只 INSERT 复制；
--   手工/审计通道见 ops-pro/deploy/payment-sharding-fund.sql。
-- **不分片**：tcc_branch_log / idempotent_record / sequences / shard_route_state。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS shard_route_state (
    domain     VARCHAR(64) NOT NULL PRIMARY KEY,
    active     BOOLEAN     NOT NULL,
    updated_at BIGINT      NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- P1 · G 泳道交易产品工作流（T-3/T-1/T-2/T-4）新表：**全部单库不分片**
--   order_id / request_id / biz_no 是全局幂等键，分片后 DB 唯一键无法跨片保证；
--   且这些表含 UPDATE（影子写只 INSERT，分片会破坏回滚一致性）。
--   分片表只有 trade_order_0..3（按 merchant_id），新增 4 列由 TradeSchemaUpgrade 同步补齐。
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_preauth', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_preauth_complete', 0);

CREATE TABLE IF NOT EXISTS escrow_confirm_log (
    id               VARCHAR(32) PRIMARY KEY,
    order_id         VARCHAR(32) NOT NULL,
    payment_id       VARCHAR(32),
    amount           BIGINT      NOT NULL,
    fee              BIGINT      NOT NULL DEFAULT 0,
    type             VARCHAR(16) NOT NULL,
    biz_no           VARCHAR(64) NOT NULL,
    hold_account_id  VARCHAR(32),
    payee_account_id VARCHAR(32),
    operator         VARCHAR(64),
    created_at       BIGINT      NOT NULL,
    UNIQUE INDEX uk_escrow_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS preauth_record (
    id               VARCHAR(32) PRIMARY KEY,
    order_id         VARCHAR(32) NOT NULL,
    account_id       VARCHAR(32) NOT NULL,
    auth_amount      BIGINT      NOT NULL,
    completed_amount BIGINT      NOT NULL DEFAULT 0,
    status           VARCHAR(24) NOT NULL,
    freeze_id        VARCHAR(32),
    request_id       VARCHAR(64),
    created_at       BIGINT      NOT NULL,
    updated_at       BIGINT,
    UNIQUE INDEX uk_preauth_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS preauth_complete_log (
    id                 VARCHAR(32) PRIMARY KEY,
    order_id           VARCHAR(32) NOT NULL,
    amount             BIGINT      NOT NULL,
    biz_no             VARCHAR(64) NOT NULL,
    request_id         VARCHAR(64) NOT NULL,
    released_freeze_id VARCHAR(32),
    refreeze_id        VARCHAR(32),
    status             VARCHAR(16),
    created_at         BIGINT      NOT NULL,
    UNIQUE INDEX uk_preauth_complete_req (request_id),
    UNIQUE INDEX uk_preauth_complete_biz (biz_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS order_expire_log (
    id          VARCHAR(32) PRIMARY KEY,
    order_id    VARCHAR(32) NOT NULL,
    closed_at   BIGINT      NOT NULL,
    type        VARCHAR(16) NOT NULL,
    reopened_at BIGINT,
    created_at  BIGINT      NOT NULL,
    UNIQUE INDEX uk_expire_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS trade_system_account (
    name       VARCHAR(32) PRIMARY KEY,
    member_id  VARCHAR(32),
    account_id VARCHAR(32),
    created_at BIGINT      NOT NULL,
    updated_at BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until BIGINT       NOT NULL,
    locked_at  BIGINT       NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- ---------------------------------------------------------------------------
-- P2 · H 泳道 T-5/T-6（合并支付/分阶段 + 撤销/改价）：只增列/只增表。
-- trade_order 新增 6 列由 TradeSchemaUpgrade 同步补到原单表与 trade_order_0..3；
-- 新表全部单库不分片（request_id/order_id 全局幂等键 + 含 UPDATE）。
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_merge', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_stage_log', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_price_log', 0);

CREATE TABLE IF NOT EXISTS merge_order (
    id               VARCHAR(32) PRIMARY KEY,
    merchant_id      VARCHAR(32) NOT NULL,
    total_amount     BIGINT      NOT NULL,
    status           VARCHAR(16) NOT NULL,
    request_id       VARCHAR(64) NOT NULL,
    carrier_order_id VARCHAR(32),
    payment_id       VARCHAR(32),
    order_count      INT         NOT NULL,
    paid_count       INT         NOT NULL DEFAULT 0,
    created_at       BIGINT      NOT NULL,
    paid_at          BIGINT,
    UNIQUE INDEX uk_merge_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS merge_order_rel (
    merge_id VARCHAR(32) NOT NULL,
    order_id VARCHAR(32) NOT NULL,
    amount   BIGINT      NOT NULL,
    fee      BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (merge_id, order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS order_stage_plan (
    order_id  VARCHAR(32) NOT NULL,
    stage_seq INT         NOT NULL,
    amount    BIGINT      NOT NULL,
    PRIMARY KEY (order_id, stage_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS stage_pay_log (
    id         VARCHAR(32) PRIMARY KEY,
    order_id   VARCHAR(32) NOT NULL,
    stage_seq  INT         NOT NULL,
    amount     BIGINT      NOT NULL,
    payment_id VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    status     VARCHAR(16) NOT NULL,
    created_at BIGINT      NOT NULL,
    updated_at BIGINT      NOT NULL,
    UNIQUE INDEX uk_stage_payment (payment_id),
    UNIQUE INDEX uk_stage_request (request_id),
    INDEX idx_stage_order_seq (order_id, stage_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS order_price_log (
    id                  VARCHAR(32) PRIMARY KEY,
    order_id            VARCHAR(32) NOT NULL,
    old_amount          BIGINT      NOT NULL,
    new_amount          BIGINT      NOT NULL,
    delta               BIGINT      NOT NULL,
    direction           VARCHAR(16) NOT NULL,
    reason              VARCHAR(256),
    operator            VARCHAR(64),
    request_id          VARCHAR(64) NOT NULL,
    refund_id           VARCHAR(32),
    supplement_order_id VARCHAR(32),
    created_at          BIGINT      NOT NULL,
    UNIQUE INDEX uk_price_request (request_id),
    INDEX idx_price_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
