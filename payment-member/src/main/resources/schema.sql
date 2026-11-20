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

-- ---------- S-2/G-1 控制台认证 + RBAC + 审计（console_* 与业务会员同库分离） ----------
CREATE SEQUENCE IF NOT EXISTS seq_console_user START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_console_role START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_audit START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_member_cert START WITH 1;

CREATE TABLE IF NOT EXISTS console_user (
    id            VARCHAR(32) PRIMARY KEY,
    username      VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,
    status        VARCHAR(16) NOT NULL,
    created_at    BIGINT      NOT NULL
);

CREATE TABLE IF NOT EXISTS console_role (
    id   VARCHAR(32) PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS console_user_role (
    user_id VARCHAR(32) NOT NULL,
    role_id VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS role_permission (
    role_id    VARCHAR(32) NOT NULL,
    permission VARCHAR(64) NOT NULL,
    PRIMARY KEY (role_id, permission)
);

CREATE TABLE IF NOT EXISTS audit_log (
    id         VARCHAR(32) PRIMARY KEY,
    username   VARCHAR(64),
    action     VARCHAR(32) NOT NULL,
    method     VARCHAR(8),
    path       VARCHAR(256),
    biz_no     VARCHAR(64),
    detail     CLOB,
    result     VARCHAR(16),
    ip         VARCHAR(64),
    trace_id   VARCHAR(64),
    created_at BIGINT      NOT NULL
);

-- ---------- M-1 密钥多版本（密文落库，主密钥经 SecretProvider） ----------
CREATE TABLE IF NOT EXISTS member_secret_version (
    member_id     VARCHAR(32)  NOT NULL,
    version       INT          NOT NULL,
    secret_cipher VARCHAR(512) NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    created_at    BIGINT       NOT NULL,
    retired_at    BIGINT,
    grace_until   BIGINT,
    PRIMARY KEY (member_id, version)
);

-- ---------- M-1/S-1 商户应用公钥证书（RSA2 + 到期告警） ----------
CREATE TABLE IF NOT EXISTS member_cert (
    id         VARCHAR(32) PRIMARY KEY,
    member_id  VARCHAR(32) NOT NULL,
    cert_sn    VARCHAR(64),
    cert_pem   CLOB        NOT NULL,
    public_key CLOB,
    not_before BIGINT,
    not_after  BIGINT,
    status     VARCHAR(16) NOT NULL,
    created_at BIGINT      NOT NULL
);

-- ---------- P1 C 泳道 additive：M-2 KYC / M-4 进件 / X-2 审核流 / M-5 C 端认证 ----------
CREATE SEQUENCE IF NOT EXISTS seq_kyc START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_application START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_contract START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_settle_card START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_review_task START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_sms START WITH 1;

-- M-2：敏感要素 SHA-256 哈希 + 掩码，绝不落明文
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
    CONSTRAINT uk_kyc_member UNIQUE (member_id)
);

-- M-4：进件单（执照号哈希唯一幂等）
CREATE TABLE IF NOT EXISTS merchant_application (
    id               VARCHAR(32) PRIMARY KEY,
    merchant_name    VARCHAR(64) NOT NULL,
    license_no_hash  VARCHAR(64) NOT NULL,
    license_no_mask  VARCHAR(32),
    legal_person_mask VARCHAR(64),
    contact_mask     VARCHAR(64),
    qualification    CLOB,
    status           VARCHAR(16) NOT NULL,
    reject_reason    VARCHAR(256),
    member_id        VARCHAR(32),
    submitter        VARCHAR(64),
    created_at       BIGINT      NOT NULL,
    reviewed_at      BIGINT,
    updated_at       BIGINT,
    CONSTRAINT uk_application_license UNIQUE (license_no_hash)
);

CREATE TABLE IF NOT EXISTS merchant_contract (
    id             VARCHAR(32) PRIMARY KEY,
    application_id VARCHAR(32) NOT NULL,
    merchant_id    VARCHAR(32) NOT NULL,
    products       VARCHAR(256),
    fee_bps        INT,
    status         VARCHAR(16) NOT NULL,
    operator       VARCHAR(64),
    signed_at      BIGINT,
    CONSTRAINT uk_contract_application UNIQUE (application_id)
);

CREATE TABLE IF NOT EXISTS merchant_settle_card (
    id           VARCHAR(32) PRIMARY KEY,
    merchant_id  VARCHAR(32) NOT NULL,
    card_no_hash VARCHAR(64) NOT NULL,
    card_no_mask VARCHAR(32),
    bank_name    VARCHAR(64),
    status       VARCHAR(16) NOT NULL,
    created_at   BIGINT      NOT NULL,
    CONSTRAINT uk_settle_card UNIQUE (merchant_id, card_no_hash)
);

-- X-2：审核任务（只追加留痕）
CREATE TABLE IF NOT EXISTS review_task (
    id             VARCHAR(32) PRIMARY KEY,
    application_id VARCHAR(32) NOT NULL,
    reviewer       VARCHAR(64),
    status         VARCHAR(16) NOT NULL,
    opinion        VARCHAR(512),
    created_at     BIGINT      NOT NULL,
    reviewed_at    BIGINT
);

-- M-5：C 端登录凭证（区别于 console_user；口令 BCrypt）
CREATE TABLE IF NOT EXISTS user_credential (
    member_id     VARCHAR(32) PRIMARY KEY,
    mobile_hash   VARCHAR(64),
    mobile_mask   VARCHAR(16),
    password_hash VARCHAR(128) NOT NULL,
    fail_count    INT          NOT NULL DEFAULT 0,
    locked_until  BIGINT       NOT NULL DEFAULT 0,
    created_at    BIGINT       NOT NULL,
    updated_at    BIGINT,
    CONSTRAINT uk_credential_mobile UNIQUE (mobile_hash)
);

-- M-5：短信验证码（沙箱，哈希存储一次性使用）
CREATE TABLE IF NOT EXISTS member_sms_code (
    id          VARCHAR(32) PRIMARY KEY,
    mobile_hash VARCHAR(64) NOT NULL,
    code_hash   VARCHAR(64) NOT NULL,
    expires_at  BIGINT      NOT NULL,
    used        INT         NOT NULL DEFAULT 0,
    created_at  BIGINT      NOT NULL
);

-- ---------- G-4a 开放平台（wave3 P2-B additive：全新表，不动既有表一行） ----------
CREATE SEQUENCE IF NOT EXISTS seq_openapi_log START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_openapi_sbx_account START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_openapi_sbx_txn START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_openapi_sbx_order START WITH 1;

-- 应用注册/授权/配额/计费策略（沙箱密钥只存密文；生产应用不复制密钥）
CREATE TABLE IF NOT EXISTS openapi_app (
    app_id             VARCHAR(64) PRIMARY KEY,
    merchant_id        VARCHAR(64),
    name               VARCHAR(64)  NOT NULL,
    scopes             CLOB,
    rate_limit         INT          NOT NULL DEFAULT 0,
    daily_quota        BIGINT       NOT NULL DEFAULT 0,
    unit_price         BIGINT       NOT NULL DEFAULT 0,
    sandbox            INT          NOT NULL DEFAULT 0,
    status             VARCHAR(16)  NOT NULL,
    api_version        VARCHAR(8)   NOT NULL DEFAULT 'v1',
    secret_cipher      CLOB,
    secret_fingerprint VARCHAR(64),
    operator           VARCHAR(64),
    created_at         BIGINT       NOT NULL,
    updated_at         BIGINT,
    revoked_at         BIGINT
);

-- 调用计费日聚合（只计量，绝不产生账务分录）
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
    CONSTRAINT uk_openapi_call UNIQUE (app_id, api, biz_date)
);

-- 沙箱独立账本（与生产 account/payment/trade 表零交集）
CREATE TABLE IF NOT EXISTS openapi_sandbox_account (
    account_id VARCHAR(32) PRIMARY KEY,
    app_id     VARCHAR(64) NOT NULL,
    member_id  VARCHAR(64),
    name       VARCHAR(64) NOT NULL,
    type       VARCHAR(16) NOT NULL,
    balance    BIGINT      NOT NULL DEFAULT 0,
    created_at BIGINT      NOT NULL,
    CONSTRAINT uk_sbx_account_name UNIQUE (app_id, name, type)
);

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
    CONSTRAINT uk_sbx_txn_request UNIQUE (app_id, request_id)
);

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
    CONSTRAINT uk_sbx_order_out UNIQUE (app_id, out_trade_no)
);