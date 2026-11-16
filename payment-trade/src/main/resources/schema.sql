CREATE SEQUENCE IF NOT EXISTS seq_order START WITH 1;

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