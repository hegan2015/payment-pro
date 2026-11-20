-- payment-member · MySQL（pp_member）。对空库执行一次；docker profile sql.init.mode=never。
CREATE TABLE IF NOT EXISTS sequences (
    name  VARCHAR(64) PRIMARY KEY,
    value BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_member', 0);
DROP FUNCTION IF EXISTS next_value_for;
DELIMITER //
CREATE FUNCTION next_value_for(s VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) RETURNS BIGINT
MODIFIES SQL DATA
BEGIN
    UPDATE sequences SET value = LAST_INSERT_ID(value + 1) WHERE name = s;
    RETURN LAST_INSERT_ID();
END//
DELIMITER ;

CREATE TABLE IF NOT EXISTS member (
    id         VARCHAR(32) PRIMARY KEY,
    name       VARCHAR(64) NOT NULL,
    type       VARCHAR(16) NOT NULL,
    status     VARCHAR(16) NOT NULL,
    app_id     VARCHAR(64),
    app_secret VARCHAR(64),
    created_at BIGINT      NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------- S-2/G-1 控制台认证 + RBAC + 审计（wave2 B 泳道 additive） ----------
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_console_user', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_console_role', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_audit', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_member_cert', 0);

CREATE TABLE IF NOT EXISTS console_user (
    id            VARCHAR(32) PRIMARY KEY,
    username      VARCHAR(64) NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    status        VARCHAR(16) NOT NULL,
    created_at    BIGINT      NOT NULL,
    UNIQUE KEY uk_console_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS console_role (
    id   VARCHAR(32) PRIMARY KEY,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    UNIQUE KEY uk_console_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS console_user_role (
    user_id VARCHAR(32) NOT NULL,
    role_id VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS role_permission (
    role_id    VARCHAR(32) NOT NULL,
    permission VARCHAR(64) NOT NULL,
    PRIMARY KEY (role_id, permission)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS audit_log (
    id         VARCHAR(32) PRIMARY KEY,
    username   VARCHAR(64),
    action     VARCHAR(32) NOT NULL,
    method     VARCHAR(8),
    path       VARCHAR(256),
    biz_no     VARCHAR(64),
    detail     LONGTEXT,
    result     VARCHAR(16),
    ip         VARCHAR(64),
    trace_id   VARCHAR(64),
    created_at BIGINT      NOT NULL,
    KEY idx_audit_username (username),
    KEY idx_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- M-1 密钥多版本 + 证书 ----------
CREATE TABLE IF NOT EXISTS member_secret_version (
    member_id     VARCHAR(32)  NOT NULL,
    version       INT          NOT NULL,
    secret_cipher VARCHAR(512) NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    created_at    BIGINT       NOT NULL,
    retired_at    BIGINT,
    grace_until   BIGINT,
    PRIMARY KEY (member_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS member_cert (
    id         VARCHAR(32) PRIMARY KEY,
    member_id  VARCHAR(32) NOT NULL,
    cert_sn    VARCHAR(64),
    cert_pem   LONGTEXT    NOT NULL,
    public_key LONGTEXT,
    not_before BIGINT,
    not_after  BIGINT,
    status     VARCHAR(16) NOT NULL,
    created_at BIGINT      NOT NULL,
    KEY idx_member_cert_member (member_id),
    KEY idx_member_cert_not_after (not_after)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- P1 C 泳道 additive：M-2 KYC / M-4 进件 / X-2 审核流 / M-5 C 端认证 ----------
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_kyc', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_application', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_contract', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_settle_card', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_review_task', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_sms', 0);

CREATE TABLE IF NOT EXISTS member_kyc (
    id              VARCHAR(32) PRIMARY KEY,
    member_id       VARCHAR(32) NOT NULL,
    name_hash       VARCHAR(64),
    name_mask       VARCHAR(64),
    id_card_no_hash VARCHAR(64),
    id_card_mask    VARCHAR(32),
    bank_card_no_hash VARCHAR(64),
    bank_card_mask  VARCHAR(32),
    mobile_hash     VARCHAR(64),
    mobile_mask     VARCHAR(16),
    verify_status   VARCHAR(16) NOT NULL,
    level           VARCHAR(8),
    reason          VARCHAR(256),
    verified_at     BIGINT,
    created_at      BIGINT      NOT NULL,
    updated_at      BIGINT,
    UNIQUE KEY uk_kyc_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS merchant_application (
    id               VARCHAR(32) PRIMARY KEY,
    merchant_name    VARCHAR(64) NOT NULL,
    license_no_hash  VARCHAR(64) NOT NULL,
    license_no_mask  VARCHAR(32),
    legal_person_mask VARCHAR(64),
    contact_mask     VARCHAR(64),
    qualification    LONGTEXT,
    status           VARCHAR(16) NOT NULL,
    reject_reason    VARCHAR(256),
    member_id        VARCHAR(32),
    submitter        VARCHAR(64),
    created_at       BIGINT      NOT NULL,
    reviewed_at      BIGINT,
    updated_at       BIGINT,
    UNIQUE KEY uk_application_license (license_no_hash),
    KEY idx_application_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS merchant_contract (
    id             VARCHAR(32) PRIMARY KEY,
    application_id VARCHAR(32) NOT NULL,
    merchant_id    VARCHAR(32) NOT NULL,
    products       VARCHAR(256),
    fee_bps        INT,
    status         VARCHAR(16) NOT NULL,
    operator       VARCHAR(64),
    signed_at      BIGINT,
    UNIQUE KEY uk_contract_application (application_id),
    KEY idx_contract_merchant (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS merchant_settle_card (
    id           VARCHAR(32) PRIMARY KEY,
    merchant_id  VARCHAR(32) NOT NULL,
    card_no_hash VARCHAR(64) NOT NULL,
    card_no_mask VARCHAR(32),
    bank_name    VARCHAR(64),
    status       VARCHAR(16) NOT NULL,
    created_at   BIGINT      NOT NULL,
    UNIQUE KEY uk_settle_card (merchant_id, card_no_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS review_task (
    id             VARCHAR(32) PRIMARY KEY,
    application_id VARCHAR(32) NOT NULL,
    reviewer       VARCHAR(64),
    status         VARCHAR(16) NOT NULL,
    opinion        VARCHAR(512),
    created_at     BIGINT      NOT NULL,
    reviewed_at    BIGINT,
    KEY idx_review_task_app (application_id),
    KEY idx_review_task_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_credential (
    member_id     VARCHAR(32) PRIMARY KEY,
    mobile_hash   VARCHAR(64),
    mobile_mask   VARCHAR(16),
    password_hash VARCHAR(128) NOT NULL,
    fail_count    INT          NOT NULL DEFAULT 0,
    locked_until  BIGINT       NOT NULL DEFAULT 0,
    created_at    BIGINT       NOT NULL,
    updated_at    BIGINT,
    UNIQUE KEY uk_credential_mobile (mobile_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS member_sms_code (
    id          VARCHAR(32) PRIMARY KEY,
    mobile_hash VARCHAR(64) NOT NULL,
    code_hash   VARCHAR(64) NOT NULL,
    expires_at  BIGINT      NOT NULL,
    used        INT         NOT NULL DEFAULT 0,
    created_at  BIGINT      NOT NULL,
    KEY idx_sms_mobile (mobile_hash, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- G-4a 开放平台（wave3 P2-B additive：全新表 + 序列，不动既有表一行） ----------
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_openapi_log', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_openapi_sbx_account', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_openapi_sbx_txn', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_openapi_sbx_order', 0);

CREATE TABLE IF NOT EXISTS openapi_app (
    app_id             VARCHAR(64) PRIMARY KEY,
    merchant_id        VARCHAR(64),
    name               VARCHAR(64)  NOT NULL,
    scopes             LONGTEXT,
    rate_limit         INT          NOT NULL DEFAULT 0,
    daily_quota        BIGINT       NOT NULL DEFAULT 0,
    unit_price         BIGINT       NOT NULL DEFAULT 0,
    sandbox            INT          NOT NULL DEFAULT 0,
    status             VARCHAR(16)  NOT NULL,
    api_version        VARCHAR(8)   NOT NULL DEFAULT 'v1',
    secret_cipher      LONGTEXT,
    secret_fingerprint VARCHAR(64),
    operator           VARCHAR(64),
    created_at         BIGINT       NOT NULL,
    updated_at         BIGINT,
    revoked_at         BIGINT,
    KEY idx_openapi_app_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS openapi_call_log (
    id            VARCHAR(32) PRIMARY KEY,
    app_id        VARCHAR(64) NOT NULL,
    api           VARCHAR(64) NOT NULL,
    biz_date      VARCHAR(8)  NOT NULL,
    call_count    BIGINT      NOT NULL DEFAULT 0,
    reject_count  BIGINT      NOT NULL DEFAULT 0,
    unit_price    BIGINT      NOT NULL DEFAULT 0,
    billed_amount BIGINT      NOT NULL DEFAULT 0,
    updated_at    BIGINT      NOT NULL,
    UNIQUE KEY uk_openapi_call (app_id, api, biz_date),
    KEY idx_openapi_call_date (biz_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS openapi_sandbox_account (
    account_id VARCHAR(32) PRIMARY KEY,
    app_id     VARCHAR(64) NOT NULL,
    member_id  VARCHAR(64),
    name       VARCHAR(64) NOT NULL,
    type       VARCHAR(16) NOT NULL,
    balance    BIGINT      NOT NULL DEFAULT 0,
    created_at BIGINT      NOT NULL,
    UNIQUE KEY uk_sbx_account_name (app_id, name, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS openapi_sandbox_txn (
    id               VARCHAR(32) PRIMARY KEY,
    app_id           VARCHAR(64) NOT NULL,
    biz_type         VARCHAR(16) NOT NULL,
    method           VARCHAR(16),
    payer_account_id VARCHAR(32),
    to_account_id    VARCHAR(32),
    order_id         VARCHAR(32),
    amount           BIGINT      NOT NULL,
    fee              BIGINT      NOT NULL DEFAULT 0,
    status           VARCHAR(16) NOT NULL,
    request_id       VARCHAR(64) NOT NULL,
    created_at       BIGINT      NOT NULL,
    UNIQUE KEY uk_sbx_txn_request (app_id, request_id),
    KEY idx_sbx_txn_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS openapi_sandbox_order (
    id           VARCHAR(32) PRIMARY KEY,
    app_id       VARCHAR(64) NOT NULL,
    merchant_id  VARCHAR(64),
    out_trade_no VARCHAR(64) NOT NULL,
    subject      VARCHAR(128),
    amount       BIGINT      NOT NULL,
    status       VARCHAR(16) NOT NULL,
    payment_id   VARCHAR(32),
    created_at   BIGINT      NOT NULL,
    paid_at      BIGINT,
    UNIQUE KEY uk_sbx_order_out (app_id, out_trade_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;