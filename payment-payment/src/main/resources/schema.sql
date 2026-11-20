CREATE SEQUENCE IF NOT EXISTS seq_payment START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_refund START WITH 1;
CREATE ALIAS IF NOT EXISTS next_value_for FOR "com.qoder.payment.common.db.H2Sequence.next";

CREATE TABLE IF NOT EXISTS payment (
    id                 VARCHAR(32) PRIMARY KEY,
    order_id           VARCHAR(32)  NOT NULL,
    merchant_id        VARCHAR(32)  NOT NULL,
    method             VARCHAR(16)  NOT NULL,
    payer_account_id   VARCHAR(32),
    card_no            VARCHAR(32),
    merchant_account_id VARCHAR(32) NOT NULL,
    amount             BIGINT       NOT NULL,
    fee                BIGINT       NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    refunded_amount    BIGINT       NOT NULL DEFAULT 0,
    refunded_fee       BIGINT       NOT NULL DEFAULT 0,
    channel_biz_no     VARCHAR(64),
    fail_reason        VARCHAR(256),
    request_id         VARCHAR(64)  NOT NULL,
    xid                VARCHAR(64),
    callback_url       VARCHAR(256),
    created_at         BIGINT       NOT NULL,
    CONSTRAINT uk_payment_request UNIQUE (request_id)
);
CREATE INDEX IF NOT EXISTS idx_payment_xid ON payment (xid);
CREATE INDEX IF NOT EXISTS idx_payment_order ON payment (order_id);

-- 单据类型：TRADE 商户交易（默认）/ TOPUP 充值 / WITHDRAW 提现。
ALTER TABLE payment ADD COLUMN IF NOT EXISTS biz_type VARCHAR(16) NOT NULL DEFAULT 'TRADE';

-- S-5/P-1 查询补单留痕：只累加计数与时间戳，状态翻转仍只经 TCC 完结监听。
ALTER TABLE payment ADD COLUMN IF NOT EXISTS reconcile_count INT NOT NULL DEFAULT 0;
ALTER TABLE payment ADD COLUMN IF NOT EXISTS last_reconcile_at BIGINT;

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
    CONSTRAINT uk_refund_request UNIQUE (request_id)
);
CREATE INDEX IF NOT EXISTS idx_refund_xid ON refund_record (xid);

CREATE TABLE IF NOT EXISTS outbox (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    event         VARCHAR(32)  NOT NULL,
    biz_id        VARCHAR(64)  NOT NULL,
    payload       CLOB         NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts      INT          NOT NULL DEFAULT 0,
    next_retry_at BIGINT       NOT NULL DEFAULT 0,
    last_error    VARCHAR(256),
    created_at    BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox (status, next_retry_at);

-- I-4 事务消息 + 多 topic（additive，幂等键不变）：路由 topic / 半消息回执 msgId / 回查判定留痕。
-- 投递判定仍只认 event+biz_id+status；3s 轮询兜底永不删除，outbox 仍是唯一事实来源。
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS topic VARCHAR(32);
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS msg_id VARCHAR(64);
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS half_state VARCHAR(16);

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
    CONSTRAINT uk_dtx_biz UNIQUE (biz_type, biz_no)
);
CREATE TABLE IF NOT EXISTS dtx_branch (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    xid         VARCHAR(64) NOT NULL,
    branch_id   VARCHAR(80) NOT NULL,
    participant VARCHAR(32) NOT NULL,
    action      VARCHAR(32) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    context     CLOB,
    seq         INT         NOT NULL,
    CONSTRAINT uk_dtx_branch UNIQUE (xid, branch_id)
);
CREATE INDEX IF NOT EXISTS idx_dtx_recover ON dtx_transaction (status, next_retry_at);
-- S-7 大额双人复核（资金防线）：UNIQUE(biz_type,biz_no) 幂等，状态翻转只经 PENDING 的 CAS。
CREATE SEQUENCE IF NOT EXISTS seq_review START WITH 1;
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
    CONSTRAINT uk_review_biz UNIQUE (biz_type, biz_no)
);
CREATE INDEX IF NOT EXISTS idx_review_status ON amount_review (status);

-- S-7 复核幂等键 (REVIEW,bizType:bizNo) 的本地幂等表（payment 域首次接入 IdempotentTemplate）。
-- biz_no 放宽到 128：键为 bizType:bizNo 拼接（requestId 最长 64）。
CREATE TABLE IF NOT EXISTS idempotent_record (
    biz_type   VARCHAR(32)  NOT NULL,
    biz_no     VARCHAR(128) NOT NULL,
    result     CLOB,
    created_at BIGINT       NOT NULL,
    PRIMARY KEY (biz_type, biz_no)
);

-- I-3 分布式调度 ShedLock 锁表（BIGINT 毫秒，与 MySQL 同构；作业名全局唯一，多实例互斥）。
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until BIGINT       NOT NULL,
    locked_at  BIGINT       NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

-- I-5b 跨片全局幂等索引（E-7）：**单库不分片**。local 单库单表时 uk_payment_request 本身即全局唯一，
-- 本表不参与受理路径（闸门仅在分片态启用），建表只为 mapper SQL 两端通用 + 结构同构。
CREATE TABLE IF NOT EXISTS payment_request_index (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY,
    payment_id VARCHAR(32) NOT NULL,
    shard      INT         NOT NULL,
    created_at BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pri_payment ON payment_request_index (payment_id);

CREATE TABLE IF NOT EXISTS refund_request_index (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY,
    refund_id  VARCHAR(32) NOT NULL,
    payment_id VARCHAR(32) NOT NULL,
    shard      INT         NOT NULL,
    created_at BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rri_refund ON refund_request_index (refund_id);

-- I-5b 分片路由状态（单库不分片）：切流/回滚跨重启不丢权威侧。local 不写不读。
CREATE TABLE IF NOT EXISTS shard_route_state (
    domain     VARCHAR(64) NOT NULL PRIMARY KEY,
    active     BOOLEAN     NOT NULL,
    updated_at BIGINT      NOT NULL
);

-- ---------------------------------------------------------------------------
-- P1 · I 泳道支付编排工作流（P-2/P-3/P-4/P-5 + CHALLENGE 续付）——全部 additive。
-- 新表一律**单库不分片**（docker 列入 payment.sharding.single-tables）；
-- 既有表只 ADD COLUMN，绝不改写既有行；状态翻转仍只经 TCC 完结监听/CAS。
-- ---------------------------------------------------------------------------

-- P-2 组合支付 / P-6 路由 / P-5 关闭撤销：payment 增列
ALTER TABLE payment ADD COLUMN IF NOT EXISTS pay_mode VARCHAR(16) NOT NULL DEFAULT 'SINGLE';
ALTER TABLE payment ADD COLUMN IF NOT EXISTS channel_code VARCHAR(32);
ALTER TABLE payment ADD COLUMN IF NOT EXISTS close_type VARCHAR(16);
ALTER TABLE payment ADD COLUMN IF NOT EXISTS closed_at BIGINT;

-- P-4 退款异步化：refund_record 增列（渠道查询键/补单留痕/失败原因/组合腿分摊快照）
ALTER TABLE refund_record ADD COLUMN IF NOT EXISTS channel_order_no VARCHAR(64);
ALTER TABLE refund_record ADD COLUMN IF NOT EXISTS reconcile_count INT NOT NULL DEFAULT 0;
ALTER TABLE refund_record ADD COLUMN IF NOT EXISTS last_reconcile_at BIGINT;
ALTER TABLE refund_record ADD COLUMN IF NOT EXISTS fail_reason VARCHAR(256);
ALTER TABLE refund_record ADD COLUMN IF NOT EXISTS leg_alloc_json CLOB;

-- P-2 组合支付腿（每腿账务 bizNo=legId，UNIQUE(payment_id,leg_seq)）
CREATE SEQUENCE IF NOT EXISTS seq_leg START WITH 1;
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
    CONSTRAINT uk_leg_payment_seq UNIQUE (payment_id, leg_seq)
);
CREATE INDEX IF NOT EXISTS idx_leg_payment ON payment_leg (payment_id);

-- P-3 分账编排（payment 域算比例建单；settle 域只收结果守恒校验登记划转）
CREATE SEQUENCE IF NOT EXISTS seq_share START WITH 1;
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
    CONSTRAINT uk_share_request UNIQUE (request_id)
);
CREATE INDEX IF NOT EXISTS idx_share_payment ON share_order (payment_id);
CREATE INDEX IF NOT EXISTS idx_share_due ON share_order (status, execute_at);

CREATE TABLE IF NOT EXISTS share_detail (
    id                  VARCHAR(32) PRIMARY KEY,
    share_id            VARCHAR(32) NOT NULL,
    receiver_account_id VARCHAR(32) NOT NULL,
    amount              BIGINT      NOT NULL,
    status              VARCHAR(16) NOT NULL,
    biz_no              VARCHAR(64) NOT NULL,
    created_at          BIGINT      NOT NULL,
    CONSTRAINT uk_share_receiver UNIQUE (share_id, receiver_account_id)
);

-- P-5 关闭/撤销留痕（UNIQUE(payment_id,type) 幂等审计）
CREATE SEQUENCE IF NOT EXISTS seq_close_log START WITH 1;
CREATE TABLE IF NOT EXISTS payment_close_log (
    id         VARCHAR(32) PRIMARY KEY,
    payment_id VARCHAR(32) NOT NULL,
    type       VARCHAR(16) NOT NULL,
    request_id VARCHAR(64),
    biz_no     VARCHAR(64),
    created_at BIGINT      NOT NULL,
    CONSTRAINT uk_close_payment_type UNIQUE (payment_id, type)
);

-- 风控 CHALLENGE 续付留痕 + 单_use 续付授权（绑定 orderId+payerKey+amount+续付 requestId）
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
    consumed_at         BIGINT
);
CREATE INDEX IF NOT EXISTS idx_challenge_order ON payment_challenge (order_id, payer_key, status);
CREATE INDEX IF NOT EXISTS idx_challenge_continue ON payment_challenge (continue_request_id);

-- ============================================================================
-- P2-E P-7 营销抵扣编排（additive：只增列/增表，绝不改写既有行）
--   payment.amount 仍是订单全额（fee 基数 / settle / 对账口径一字不改）；
--   marketing_amount = 营销净抵扣（退回后收敛），净实付 = amount − marketing_amount。
-- ============================================================================
ALTER TABLE payment ADD COLUMN IF NOT EXISTS marketing_amount BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payment ADD COLUMN IF NOT EXISTS marketing_status VARCHAR(16) NOT NULL DEFAULT 'NONE';

CREATE SEQUENCE IF NOT EXISTS seq_marketing_leg START WITH 1;
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
    CONSTRAINT uk_mktleg_source UNIQUE (payment_id, source, ref_id)
);
CREATE INDEX IF NOT EXISTS idx_mktleg_payment ON payment_marketing_leg (payment_id);

-- ============================================================================
-- P2-H T-5 合并支付/分阶段（additive：只增列，既有列/唯一键/fee 口径一字未改）
--   stage_seq 仅 STAGE 单填充；merge_id 仅 MERGE/MERGE_COMBO 单填充。
-- ============================================================================
ALTER TABLE payment ADD COLUMN IF NOT EXISTS stage_seq INT;
ALTER TABLE payment ADD COLUMN IF NOT EXISTS merge_id VARCHAR(32);
