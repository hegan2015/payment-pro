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
    CONSTRAINT uk_payment_request UNIQUE (request_id),
    INDEX idx_payment_xid (xid),
    INDEX idx_payment_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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