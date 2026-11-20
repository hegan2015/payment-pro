-- payment-payment · MySQL（pp_payment）。对空库执行一次；docker profile sql.init.mode=never。
-- 含 TCC 协调器日志 dtx_transaction/dtx_branch 与事务性 outbox。
CREATE TABLE IF NOT EXISTS sequences (
    name  VARCHAR(64) PRIMARY KEY,
    value BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_payment', 0), ('seq_refund', 0);
DROP FUNCTION IF EXISTS next_value_for;
DELIMITER //
CREATE FUNCTION next_value_for(s VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) RETURNS BIGINT
MODIFIES SQL DATA
BEGIN
    UPDATE sequences SET value = LAST_INSERT_ID(value + 1) WHERE name = s;
    RETURN LAST_INSERT_ID();
END//
DELIMITER ;

CREATE TABLE IF NOT EXISTS payment (
    id                  VARCHAR(32) PRIMARY KEY,
    order_id            VARCHAR(32)  NOT NULL,
    merchant_id         VARCHAR(32)  NOT NULL,
    biz_type            VARCHAR(16)  NOT NULL DEFAULT 'TRADE',
    method              VARCHAR(16)  NOT NULL,
    payer_account_id    VARCHAR(32),
    card_no             VARCHAR(32),
    merchant_account_id VARCHAR(32)  NOT NULL,
    amount              BIGINT       NOT NULL,
    fee                 BIGINT       NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    refunded_amount     BIGINT       NOT NULL DEFAULT 0,
    refunded_fee        BIGINT       NOT NULL DEFAULT 0,
    channel_biz_no      VARCHAR(64),
    fail_reason         VARCHAR(256),
    request_id          VARCHAR(64)  NOT NULL,
    xid                 VARCHAR(64),
    callback_url        VARCHAR(256),
    created_at          BIGINT       NOT NULL,
    reconcile_count     INT          NOT NULL DEFAULT 0,
    last_reconcile_at   BIGINT,
    CONSTRAINT uk_payment_request UNIQUE (request_id),
    INDEX idx_payment_xid (xid),
    INDEX idx_payment_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- S-5/P-1 补单留痕列（reconcile_count/last_reconcile_at）：docker 现存库不会重跑 init
-- （sql.init.mode=never），由 PaymentSchemaUpgrade 启动时幂等补齐（只 ADD COLUMN，绝不改写既有数据）。
-- tcc-core WAITING 增量无需改表：dtx_transaction.status 已是 VARCHAR(16)。

CREATE TABLE IF NOT EXISTS refund_record (
    id         VARCHAR(32) PRIMARY KEY,
    payment_id VARCHAR(32) NOT NULL,
    amount     BIGINT      NOT NULL,
    fee_refund BIGINT      NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    xid        VARCHAR(64),
    status     VARCHAR(16) NOT NULL,
    biz_no     VARCHAR(64) NOT NULL,
    created_at BIGINT      NOT NULL,
    CONSTRAINT uk_refund_request UNIQUE (request_id),
    INDEX idx_refund_xid (xid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS outbox (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    event         VARCHAR(32)  NOT NULL,
    biz_id        VARCHAR(64)  NOT NULL,
    payload       LONGTEXT     NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts      INT          NOT NULL DEFAULT 0,
    next_retry_at BIGINT       NOT NULL DEFAULT 0,
    last_error    VARCHAR(256),
    created_at    BIGINT       NOT NULL,
    -- I-4 事务消息 + 多 topic（additive，幂等键不变）：路由 topic / 半消息回执 / 回查判定留痕。
    -- docker 现存库不会重跑 init（sql.init.mode=never），由 PaymentSchemaUpgrade 启动时逐列幂等补齐。
    topic         VARCHAR(32),
    msg_id        VARCHAR(64),
    half_state    VARCHAR(16),
    INDEX idx_outbox_pending (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dtx_transaction (
    xid           VARCHAR(64) PRIMARY KEY,
    biz_type      VARCHAR(32) NOT NULL,
    biz_no        VARCHAR(64) NOT NULL,
    status        VARCHAR(16) NOT NULL,
    fail_reason   VARCHAR(256),
    retry_count   INT         NOT NULL DEFAULT 0,
    next_retry_at BIGINT      NOT NULL DEFAULT 0,
    version       INT         NOT NULL DEFAULT 0,
    created_at    BIGINT      NOT NULL,
    updated_at    BIGINT      NOT NULL,
    CONSTRAINT uk_dtx_biz UNIQUE (biz_type, biz_no),
    INDEX idx_dtx_recover (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dtx_branch (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    xid         VARCHAR(64) NOT NULL,
    branch_id   VARCHAR(80) NOT NULL,
    participant VARCHAR(32) NOT NULL,
    action      VARCHAR(32) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    context     LONGTEXT,
    seq         INT         NOT NULL,
    CONSTRAINT uk_dtx_branch UNIQUE (xid, branch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- S-7 大额双人复核（资金防线）。docker 现存库由 PaymentSchemaUpgrade 启动时幂等补建（只增不改）。
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_review', 0);
CREATE TABLE IF NOT EXISTS amount_review (
    id            VARCHAR(32) PRIMARY KEY,
    biz_type      VARCHAR(16)  NOT NULL,
    biz_no        VARCHAR(64)  NOT NULL,
    amount        BIGINT       NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    submitter     VARCHAR(64)  NOT NULL,
    approver      VARCHAR(64),
    reject_reason VARCHAR(256),
    created_at    BIGINT       NOT NULL,
    reviewed_at   BIGINT,
    CONSTRAINT uk_review_biz UNIQUE (biz_type, biz_no),
    INDEX idx_review_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- S-7 复核幂等键 (REVIEW,bizType:bizNo) 本地幂等表（biz_no 128：bizType:bizNo 拼接键）。
CREATE TABLE IF NOT EXISTS idempotent_record (
    biz_type   VARCHAR(32)  NOT NULL,
    biz_no     VARCHAR(128) NOT NULL,
    result     LONGTEXT,
    created_at BIGINT       NOT NULL,
    PRIMARY KEY (biz_type, biz_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- I-3 分布式调度 ShedLock 锁表（BIGINT 毫秒）。docker 现存库由 ShedLockSchemaInitializer 启动时幂等补建（只增不改）。
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until BIGINT       NOT NULL,
    locked_at  BIGINT       NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- I-5b 资金域分库分表（单库分表方案）新增支撑表：**全部单库不分片**
--   payment_request_index / refund_request_index：跨片全局幂等索引（E-7 核心）
--   shard_route_state：切流/回滚路由状态持久化（重启不丢权威侧）
-- 分片表 payment_0..3 / refund_record_0..3 由 ShardTableBootstrap 启动时
--   CREATE TABLE IF NOT EXISTS ... LIKE 原表 + 只 INSERT 复制建立（原表原行全部保留），
--   手工/审计通道见 ops-pro/deploy/payment-sharding-fund.sql。
-- 协调器表 dtx_transaction / dtx_branch / outbox 与 idempotent_record / sequences /
--   shedlock / amount_review **绝不碎片**（终态 + 事务 outbox 原子性）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payment_request_index (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY,
    payment_id VARCHAR(32) NOT NULL,
    shard      INT         NOT NULL,
    created_at BIGINT      NOT NULL,
    INDEX idx_pri_payment (payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS refund_request_index (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY,
    refund_id  VARCHAR(32) NOT NULL,
    payment_id VARCHAR(32) NOT NULL,
    shard      INT         NOT NULL,
    created_at BIGINT      NOT NULL,
    INDEX idx_rri_refund (refund_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS shard_route_state (
    domain     VARCHAR(64) NOT NULL PRIMARY KEY,
    active     BOOLEAN     NOT NULL,
    updated_at BIGINT      NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- P1 · I 泳道支付编排工作流（P-2/P-3/P-4/P-5 + CHALLENGE 续付）——全部 additive。
-- docker 现存库不重跑本脚本（sql.init.mode=never），由 PaymentSchemaUpgrade 启动时
-- 幂等补列/补建（只增不改，绝不 UPDATE/DELETE 既有行）。新表一律单库不分片。
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_leg', 0), ('seq_share', 0), ('seq_close_log', 0);

CREATE TABLE IF NOT EXISTS payment_leg (
    id               VARCHAR(32) PRIMARY KEY,
    payment_id       VARCHAR(32) NOT NULL,
    leg_seq          INT         NOT NULL,
    method           VARCHAR(16) NOT NULL,
    payer_account_id VARCHAR(32),
    card_no          VARCHAR(32),
    amount           BIGINT      NOT NULL,
    fee              BIGINT      NOT NULL DEFAULT 0,
    status           VARCHAR(16) NOT NULL,
    channel_biz_no   VARCHAR(64),
    channel_code     VARCHAR(32),
    refunded_amount  BIGINT      NOT NULL DEFAULT 0,
    refunded_fee     BIGINT      NOT NULL DEFAULT 0,
    created_at       BIGINT      NOT NULL,
    CONSTRAINT uk_leg_payment_seq UNIQUE (payment_id, leg_seq),
    INDEX idx_leg_payment (payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS share_order (
    id                  VARCHAR(32) PRIMARY KEY,
    payment_id          VARCHAR(32) NOT NULL,
    merchant_id         VARCHAR(32) NOT NULL,
    merchant_account_id VARCHAR(32) NOT NULL,
    total_amount        BIGINT      NOT NULL,
    share_type          VARCHAR(16) NOT NULL,
    status              VARCHAR(16) NOT NULL,
    request_id          VARCHAR(64) NOT NULL,
    biz_no              VARCHAR(64) NOT NULL,
    execute_at          BIGINT      NOT NULL,
    created_at          BIGINT      NOT NULL,
    shared_at           BIGINT,
    backed_at           BIGINT,
    back_request_id     VARCHAR(64),
    fail_reason         VARCHAR(256),
    CONSTRAINT uk_share_request UNIQUE (request_id),
    INDEX idx_share_payment (payment_id),
    INDEX idx_share_due (status, execute_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS share_detail (
    id                  VARCHAR(32) PRIMARY KEY,
    share_id            VARCHAR(32) NOT NULL,
    receiver_account_id VARCHAR(32) NOT NULL,
    amount              BIGINT      NOT NULL,
    status              VARCHAR(16) NOT NULL,
    biz_no              VARCHAR(64) NOT NULL,
    created_at          BIGINT      NOT NULL,
    CONSTRAINT uk_share_receiver UNIQUE (share_id, receiver_account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS payment_close_log (
    id         VARCHAR(32) PRIMARY KEY,
    payment_id VARCHAR(32) NOT NULL,
    type       VARCHAR(16) NOT NULL,
    request_id VARCHAR(64),
    biz_no     VARCHAR(64),
    created_at BIGINT      NOT NULL,
    CONSTRAINT uk_close_payment_type UNIQUE (payment_id, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS payment_challenge (
    request_id          VARCHAR(64) PRIMARY KEY,
    order_id            VARCHAR(32) NOT NULL,
    payer_key           VARCHAR(64) NOT NULL,
    amount              BIGINT      NOT NULL,
    method              VARCHAR(16) NOT NULL,
    status              VARCHAR(16) NOT NULL,
    continue_request_id VARCHAR(64),
    created_at          BIGINT      NOT NULL,
    expire_at           BIGINT      NOT NULL,
    consumed_at         BIGINT,
    INDEX idx_challenge_order (order_id, payer_key, status),
    INDEX idx_challenge_continue (continue_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- P2-E P-7 营销抵扣编排（additive，单库不分片）：payment.marketing_amount / marketing_status
-- 两列由 PaymentSchemaUpgrade 启动时幂等补齐（含 payment_0..N 分片表同步），绝不改写既有行。
-- ============================================================================
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_marketing_leg', 0);
CREATE TABLE IF NOT EXISTS payment_marketing_leg (
    id          VARCHAR(32) PRIMARY KEY,
    payment_id  VARCHAR(32)  NOT NULL,
    leg_seq     INT          NOT NULL,
    source      VARCHAR(16)  NOT NULL,
    ref_id      VARCHAR(64)  NOT NULL,
    use_id      VARCHAR(64),
    biz_no      VARCHAR(128),
    biz_key     VARCHAR(64),
    amount      BIGINT       NOT NULL DEFAULT 0,
    points      BIGINT,
    status      VARCHAR(16)  NOT NULL,
    fail_reason VARCHAR(256),
    created_at  BIGINT       NOT NULL,
    updated_at  BIGINT       NOT NULL,
    CONSTRAINT uk_mktleg_seq UNIQUE (payment_id, leg_seq),
    CONSTRAINT uk_mktleg_source UNIQUE (payment_id, source, ref_id),
    INDEX idx_mktleg_payment (payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- P2-H T-5 合并支付/分阶段（additive：只增列；新库/存量库均由 PaymentSchemaUpgrade 启动时
--   幂等补齐 payment.stage_seq / payment.merge_id，含 payment_0..N 分片表同步；绝不改写既有行）
-- ============================================================================
