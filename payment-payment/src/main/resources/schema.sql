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