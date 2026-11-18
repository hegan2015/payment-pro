CREATE SEQUENCE IF NOT EXISTS seq_member START WITH 1;
CREATE ALIAS IF NOT EXISTS next_value_for FOR "com.qoder.payment.common.db.H2Sequence.next";

CREATE TABLE IF NOT EXISTS member (
    id         VARCHAR(32) PRIMARY KEY,
    name       VARCHAR(64) NOT NULL,
    type       VARCHAR(16) NOT NULL,
    status     VARCHAR(16) NOT NULL,
    app_id     VARCHAR(64),
    app_secret VARCHAR(64),
    created_at BIGINT      NOT NULL
);