CREATE SEQUENCE IF NOT EXISTS seq_recon START WITH 1;
CREATE ALIAS IF NOT EXISTS next_value_for FOR "com.qoder.payment.common.db.H2Sequence.next";

CREATE TABLE IF NOT EXISTS recon_report (
    id         VARCHAR(32) PRIMARY KEY,
    checked_at BIGINT  NOT NULL,
    pass       BOOLEAN NOT NULL,
    report     CLOB    NOT NULL
);