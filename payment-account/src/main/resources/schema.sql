CREATE SEQUENCE IF NOT EXISTS seq_account START WITH 1;

CREATE TABLE IF NOT EXISTS account (
    id          VARCHAR(32) PRIMARY KEY,
    member_id   VARCHAR(32) NOT NULL,
    member_name VARCHAR(64),
    type        VARCHAR(16) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    created_at  BIGINT      NOT NULL,
    CONSTRAINT uk_account_member UNIQUE (member_id)
);