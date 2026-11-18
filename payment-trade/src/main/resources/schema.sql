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