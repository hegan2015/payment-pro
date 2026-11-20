-- payment-cashier · MySQL（pp_cashier）。docker profile sql.init.mode=never，
-- 现存/新建 pp_cashier 库由 CashierSchemaUpgrade 启动期幂等补建（本脚本为参考事实来源，可手工对空库执行一次）。
-- 建库由 datasource url createDatabaseIfNotExist=true 自动完成，不改 ops-pro。只增不改。
CREATE TABLE IF NOT EXISTS sequences (
    name  VARCHAR(64) PRIMARY KEY,
    value BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_cashier', 0);

CREATE TABLE IF NOT EXISTS pay_code (
    code               VARCHAR(64) PRIMARY KEY,
    type               VARCHAR(24) NOT NULL,
    account_id         VARCHAR(32),
    order_id           VARCHAR(32),
    amount             BIGINT      NOT NULL,
    status             VARCHAR(16) NOT NULL,
    expire_at          BIGINT      NOT NULL,
    request_id         VARCHAR(64),
    consume_request_id VARCHAR(64),
    qr_content         VARCHAR(512),
    merchant_name      VARCHAR(128),
    subject            VARCHAR(256),
    created_at         BIGINT      NOT NULL,
    consumed_at        BIGINT,
    INDEX idx_pay_code_order (order_id),
    INDEX idx_pay_code_status (status, expire_at),
    INDEX idx_pay_code_req (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pay_credential (
    member_id     VARCHAR(32) PRIMARY KEY,
    password_hash VARCHAR(128) NOT NULL,
    salt          VARCHAR(64)  NOT NULL,
    fail_count    INT          NOT NULL,
    locked_until  BIGINT,
    created_at    BIGINT       NOT NULL,
    updated_at    BIGINT       NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS verify_session (
    id          VARCHAR(64) PRIMARY KEY,
    member_id   VARCHAR(32),
    account_id  VARCHAR(32),
    order_id    VARCHAR(32),
    amount      BIGINT,
    verify_type VARCHAR(24) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    expire_at   BIGINT      NOT NULL,
    request_id  VARCHAR(64),
    created_at  BIGINT      NOT NULL,
    used_at     BIGINT,
    INDEX idx_verify_session_req (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sms_code (
    id         VARCHAR(64) PRIMARY KEY,
    member_id  VARCHAR(32),
    account_id VARCHAR(32),
    order_id   VARCHAR(32),
    code       VARCHAR(8)  NOT NULL,
    status     VARCHAR(16) NOT NULL,
    expire_at  BIGINT      NOT NULL,
    created_at BIGINT      NOT NULL,
    INDEX idx_sms_member (member_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;