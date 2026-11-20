CREATE SEQUENCE IF NOT EXISTS seq_order START WITH 1;
CREATE ALIAS IF NOT EXISTS next_value_for FOR "com.qoder.payment.common.db.H2Sequence.next";

CREATE TABLE IF NOT EXISTS trade_order (
    id              VARCHAR(32) PRIMARY KEY,
    merchant_id     VARCHAR(32)  NOT NULL,
    merchant_name   VARCHAR(64),
    subject         VARCHAR(128) NOT NULL,
    amount          BIGINT       NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    payment_id      VARCHAR(32),
    refunded_amount BIGINT       NOT NULL DEFAULT 0,
    created_at      BIGINT       NOT NULL,
    paid_at         BIGINT,
    closed_at       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_order_merchant ON trade_order (merchant_id);

-- 商户外部交易号幂等（可选字段）：同商户重复 outTradeNo 下单返回已有订单，杜绝重试孤儿单。
ALTER TABLE trade_order ADD COLUMN IF NOT EXISTS out_trade_no VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS idx_order_out_trade ON trade_order (merchant_id, out_trade_no);

-- 单据类型：TRADE 商户交易（默认）/ TOPUP 充值 / WITHDRAW 提现。
ALTER TABLE trade_order ADD COLUMN IF NOT EXISTS biz_type VARCHAR(16) NOT NULL DEFAULT 'TRADE';

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

-- I-5b 分片路由状态（单库不分片）：切流/回滚跨重启不丢权威侧。local 不写不读。
CREATE TABLE IF NOT EXISTS shard_route_state (
    domain     VARCHAR(64) NOT NULL PRIMARY KEY,
    active     BOOLEAN     NOT NULL,
    updated_at BIGINT      NOT NULL
);

-- ---------------------------------------------------------------------------
-- P1 · G 泳道交易产品工作流（T-3/T-1/T-2/T-4）：只增列/只增表，既有列与唯一键一字未改。
-- trade_order 新增 4 列（分片表 trade_order_0..3 由 TradeSchemaUpgrade 同步补列，逐列比对零差异）；
-- 以下留痕/状态表全部 **单库不分片**（order_id / request_id 是全局幂等键，分片会破坏跨片唯一性）。
-- ---------------------------------------------------------------------------
ALTER TABLE trade_order ADD COLUMN IF NOT EXISTS product_type VARCHAR(16) NOT NULL DEFAULT 'INSTANT';
ALTER TABLE trade_order ADD COLUMN IF NOT EXISTS payee_merchant_id VARCHAR(32);
ALTER TABLE trade_order ADD COLUMN IF NOT EXISTS escrow_status VARCHAR(16);
ALTER TABLE trade_order ADD COLUMN IF NOT EXISTS expire_at BIGINT;
CREATE INDEX IF NOT EXISTS idx_order_expire ON trade_order (status, expire_at);

CREATE SEQUENCE IF NOT EXISTS seq_preauth START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_preauth_complete START WITH 1;

-- T-1 担保确认收货留痕（一单一次；id=EC+orderId 确定性 ⇒ 账务 bizNo 幂等）
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
    CONSTRAINT uk_escrow_order UNIQUE (order_id)
);

-- T-2 预授权单（冻结额真值在 account A-3 冻结单 + 账务 frozen 列，本表记产品状态与累计完成额）
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
    CONSTRAINT uk_preauth_order UNIQUE (order_id)
);

-- T-2 完成扣款留痕（request_id 调用方幂等键；biz_no 账务过账幂等键）
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
    CONSTRAINT uk_preauth_complete_req UNIQUE (request_id),
    CONSTRAINT uk_preauth_complete_biz UNIQUE (biz_no)
);

-- T-4 超时关闭留痕（order_id UNIQUE ⇒ 不重不漏；reopened_at 记竞态防悬挂回滚）
CREATE TABLE IF NOT EXISTS order_expire_log (
    id          VARCHAR(32) PRIMARY KEY,
    order_id    VARCHAR(32) NOT NULL,
    closed_at   BIGINT      NOT NULL,
    type        VARCHAR(16) NOT NULL,
    reopened_at BIGINT,
    created_at  BIGINT      NOT NULL,
    CONSTRAINT uk_expire_order UNIQUE (order_id)
);

-- T-1 平台担保户登记（系统商户 SYS_ESCROW + 其余额账户，沿用 SYS_FUND 先例）
CREATE TABLE IF NOT EXISTS trade_system_account (
    name       VARCHAR(32) PRIMARY KEY,
    member_id  VARCHAR(32),
    account_id VARCHAR(32),
    created_at BIGINT      NOT NULL,
    updated_at BIGINT
);

-- I-3 ShedLock 锁表（与 common ShedLockSchemaInitializer 同一 DDL，BIGINT 毫秒）
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until BIGINT       NOT NULL,
    locked_at  BIGINT       NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
-- ---------------------------------------------------------------------------
-- P2 · H 泳道 T-5/T-6（合并支付/分阶段 + 撤销/改价）：只增列/只增表，既有列与唯一键一字未改。
-- trade_order 新增 6 列（分片表 trade_order_0..3 由 TradeSchemaUpgrade 同步补列）；
-- 以下新表全部 **单库不分片**（request_id / order_id 是全局幂等键，且含 UPDATE）。
-- ---------------------------------------------------------------------------
ALTER TABLE trade_order ADD COLUMN IF NOT EXISTS merge_id VARCHAR(32);
ALTER TABLE trade_order ADD COLUMN IF NOT EXISTS stage_count INT;
ALTER TABLE trade_order ADD COLUMN IF NOT EXISTS paid_stage INT NOT NULL DEFAULT 0;
ALTER TABLE trade_order ADD COLUMN IF NOT EXISTS paid_amount BIGINT NOT NULL DEFAULT 0;
ALTER TABLE trade_order ADD COLUMN IF NOT EXISTS original_amount BIGINT;
ALTER TABLE trade_order ADD COLUMN IF NOT EXISTS price_change_count INT NOT NULL DEFAULT 0;

CREATE SEQUENCE IF NOT EXISTS seq_merge START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_stage_log START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_price_log START WITH 1;

-- T-5 合并单（request_id UNIQUE 幂等；状态 CREATED/PAYING/PAID）
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
    CONSTRAINT uk_merge_request UNIQUE (request_id)
);

-- T-5 合并单子订单关联（amount=入合并快照，fee=受理分摊快照，Σfee=支付单 fee）
CREATE TABLE IF NOT EXISTS merge_order_rel (
    merge_id VARCHAR(32) NOT NULL,
    order_id VARCHAR(32) NOT NULL,
    amount   BIGINT      NOT NULL,
    fee      BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (merge_id, order_id)
);

-- T-5 分阶段计划（Σ期额=订单额，创建时强校验）
CREATE TABLE IF NOT EXISTS order_stage_plan (
    order_id  VARCHAR(32) NOT NULL,
    stage_seq INT         NOT NULL,
    amount    BIGINT      NOT NULL,
    PRIMARY KEY (order_id, stage_seq)
);

-- T-5 阶段支付留痕（三重唯一键：期占位/支付单/请求幂等）
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
    CONSTRAINT uk_stage_payment UNIQUE (payment_id),
    CONSTRAINT uk_stage_request UNIQUE (request_id)
);
-- 期级只建普通索引：同一期撤销后可重付（每次 attempt 独立留痕行，旧行保留 CANCELLED 终态）。
-- 双付守卫由订单级 reserveStage CAS + uk_stage_payment + uk_stage_request 承担。
CREATE INDEX IF NOT EXISTS idx_stage_order_seq ON stage_pay_log (order_id, stage_seq);

-- T-6 改价留痕（request_id UNIQUE 幂等 + 审计）
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
    CONSTRAINT uk_price_request UNIQUE (request_id)
);
CREATE INDEX IF NOT EXISTS idx_price_order ON order_price_log (order_id);
