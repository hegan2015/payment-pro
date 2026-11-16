CREATE SEQUENCE IF NOT EXISTS seq_settle START WITH 1;

CREATE TABLE IF NOT EXISTS settle_record (
    id          VARCHAR(32) PRIMARY KEY,
    type        VARCHAR(16) NOT NULL,
    biz_id      VARCHAR(64) NOT NULL,
    payment_id  VARCHAR(32) NOT NULL,
    order_id    VARCHAR(32),
    merchant_id VARCHAR(32) NOT NULL,
    gross       BIGINT      NOT NULL,
    fee         BIGINT      NOT NULL,
    net         BIGINT      NOT NULL,
    method      VARCHAR(16),
    status      VARCHAR(16) NOT NULL,
    created_at  BIGINT      NOT NULL,
    CONSTRAINT uk_settle_biz UNIQUE (biz_id, type)
);
CREATE INDEX IF NOT EXISTS idx_settle_merchant ON settle_record (merchant_id);

CREATE TABLE IF NOT EXISTS idempotent_record (
    biz_type   VARCHAR(32) NOT NULL,
    biz_no     VARCHAR(64) NOT NULL,
    result     CLOB,
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (biz_type, biz_no)
);