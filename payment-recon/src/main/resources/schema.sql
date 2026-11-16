CREATE SEQUENCE IF NOT EXISTS seq_recon START WITH 1;

CREATE TABLE IF NOT EXISTS recon_report (
    id         VARCHAR(32) PRIMARY KEY,
    checked_at BIGINT  NOT NULL,
    pass       BOOLEAN NOT NULL,
    report     CLOB    NOT NULL
);