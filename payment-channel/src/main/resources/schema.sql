CREATE TABLE IF NOT EXISTS channel_trade (
    biz_no      VARCHAR(64) PRIMARY KEY,
    type        VARCHAR(16) NOT NULL,
    orig_biz_no VARCHAR(64),
    card_no     VARCHAR(32) NOT NULL,
    amount      BIGINT      NOT NULL,
    status      VARCHAR(16) NOT NULL,
    reason      VARCHAR(256),
    created_at  BIGINT      NOT NULL
);

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