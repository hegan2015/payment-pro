-- payment-channel · MySQL（pp_channel）。对空库执行一次；docker profile sql.init.mode=never。
CREATE TABLE IF NOT EXISTS channel_trade (
    biz_no      VARCHAR(64) PRIMARY KEY,
    type        VARCHAR(16) NOT NULL,
    orig_biz_no VARCHAR(64),
    card_no     VARCHAR(32) NOT NULL,
    amount      BIGINT      NOT NULL,
    status      VARCHAR(16) NOT NULL,
    reason      VARCHAR(256),
    created_at  BIGINT      NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tcc_branch_log (
    xid        VARCHAR(64) NOT NULL,
    branch_id  VARCHAR(80) NOT NULL,
    action     VARCHAR(32) NOT NULL,
    status     VARCHAR(16) NOT NULL,
    context    LONGTEXT,
    created_at BIGINT      NOT NULL,
    updated_at BIGINT      NOT NULL,
    PRIMARY KEY (xid, branch_id, action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;