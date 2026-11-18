CREATE SEQUENCE IF NOT EXISTS seq_notify START WITH 1;
CREATE ALIAS IF NOT EXISTS next_value_for FOR "com.qoder.payment.common.db.H2Sequence.next";

CREATE TABLE IF NOT EXISTS notify_record (
    id            VARCHAR(32) PRIMARY KEY,
    event         VARCHAR(32)  NOT NULL,
    biz_id        VARCHAR(64)  NOT NULL,
    payload       CLOB         NOT NULL,
    callback_url  VARCHAR(256),
    status        VARCHAR(16)  NOT NULL,
    attempts      BIGINT       NOT NULL DEFAULT 0,
    next_retry_at BIGINT       NOT NULL DEFAULT 0,
    last_error    VARCHAR(256),
    created_at    BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_notify_biz ON notify_record (biz_id);
CREATE INDEX IF NOT EXISTS idx_notify_pending ON notify_record (status, next_retry_at);

CREATE TABLE IF NOT EXISTS idempotent_record (
    biz_type   VARCHAR(32) NOT NULL,
    biz_no     VARCHAR(64) NOT NULL,
    result     CLOB,
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (biz_type, biz_no)
);