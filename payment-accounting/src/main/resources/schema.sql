CREATE SEQUENCE IF NOT EXISTS seq_entry START WITH 1;
CREATE ALIAS IF NOT EXISTS next_value_for FOR "com.qoder.payment.common.db.H2Sequence.next";

CREATE TABLE IF NOT EXISTS account_balance (
    account_id VARCHAR(32) PRIMARY KEY,
    balance    BIGINT NOT NULL DEFAULT 0,
    frozen     BIGINT NOT NULL DEFAULT 0,
    version    INT    NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS ledger_entry (
    id            VARCHAR(32) PRIMARY KEY,
    seq           BIGINT       NOT NULL,
    biz_no        VARCHAR(64)  NOT NULL,
    account_id    VARCHAR(32)  NOT NULL,
    dir           VARCHAR(1)   NOT NULL,
    amount        BIGINT       NOT NULL,
    balance_after BIGINT       NOT NULL,
    subject       VARCHAR(128),
    ref_id        VARCHAR(64),
    created_at    BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_entry_account ON ledger_entry (account_id, seq);
CREATE INDEX IF NOT EXISTS idx_entry_biz ON ledger_entry (biz_no);

CREATE TABLE IF NOT EXISTS transfer_record (
    biz_no     VARCHAR(64) PRIMARY KEY,
    lines      CLOB         NOT NULL,
    subject    VARCHAR(128),
    ref_id     VARCHAR(64),
    reversed   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at BIGINT       NOT NULL
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

CREATE TABLE IF NOT EXISTS idempotent_record (
    biz_type   VARCHAR(32) NOT NULL,
    biz_no     VARCHAR(64) NOT NULL,
    result     CLOB,
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (biz_type, biz_no)
);