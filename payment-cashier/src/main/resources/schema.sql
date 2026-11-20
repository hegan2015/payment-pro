-- payment-cashier · local H2（data/cashier，零依赖）。P1 C-2/C-3 新增库（E-3 前置）。
-- 只增不改：CREATE TABLE/SEQUENCE IF NOT EXISTS；绝不 DROP/TRUNCATE/DELETE 既有行。
CREATE SEQUENCE IF NOT EXISTS seq_cashier START WITH 1;
CREATE ALIAS IF NOT EXISTS next_value_for FOR "com.qoder.payment.common.db.H2Sequence.next";

-- C-2 付款码/扫码（被扫 18 位数字码 / 主扫二维码令牌）；一次性核销 CAS 防重放
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
    consumed_at        BIGINT
);
CREATE INDEX IF NOT EXISTS idx_pay_code_order ON pay_code (order_id);
CREATE INDEX IF NOT EXISTS idx_pay_code_status ON pay_code (status, expire_at);
CREATE INDEX IF NOT EXISTS idx_pay_code_req ON pay_code (request_id);

-- C-3 支付口令（哈希加盐，明文不落库）；失败计数 + 锁定防暴力
CREATE TABLE IF NOT EXISTS pay_credential (
    member_id     VARCHAR(32) PRIMARY KEY,
    password_hash VARCHAR(128) NOT NULL,
    salt          VARCHAR(64)  NOT NULL,
    fail_count    INT          NOT NULL,
    locked_until  BIGINT,
    created_at    BIGINT       NOT NULL,
    updated_at    BIGINT       NOT NULL
);

-- C-3 验证会话（一次性 verifyToken，绑定 order+amount+payer，过期/核销 CAS）
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
    used_at     BIGINT
);
CREATE INDEX IF NOT EXISTS idx_verify_session_req ON verify_session (request_id);

-- C-3 短信验证码（沙箱）：一次性、短有效期
CREATE TABLE IF NOT EXISTS sms_code (
    id         VARCHAR(64) PRIMARY KEY,
    member_id  VARCHAR(32),
    account_id VARCHAR(32),
    order_id   VARCHAR(32),
    code       VARCHAR(8)  NOT NULL,
    status     VARCHAR(16) NOT NULL,
    expire_at  BIGINT      NOT NULL,
    created_at BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sms_member ON sms_code (member_id, status);