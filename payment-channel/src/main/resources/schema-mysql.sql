-- payment-channel · MySQL（pp_channel）。对空库执行一次；docker profile sql.init.mode=never。
CREATE TABLE IF NOT EXISTS channel_trade (
    biz_no      VARCHAR(64) PRIMARY KEY,
    type        VARCHAR(16) NOT NULL,
    orig_biz_no VARCHAR(64),
    card_no     VARCHAR(32) NOT NULL,
    amount      BIGINT      NOT NULL,
    status      VARCHAR(16) NOT NULL,
    reason      VARCHAR(256),
    created_at  BIGINT      NOT NULL,
    channel_order_no VARCHAR(64),
    submitted_at BIGINT,
    receipt_at   BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- CH-1 增量列（channel_order_no/submitted_at/receipt_at）与新表 channel_receipt_log：
-- docker 现存库不会重跑 init（sql.init.mode=never），由 ChannelSchemaUpgrade 启动时幂等补齐
-- （只 ADD COLUMN / CREATE TABLE IF NOT EXISTS，绝不改写既有数据），无需手工 ALTER。

CREATE TABLE IF NOT EXISTS channel_receipt_log (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    biz_no           VARCHAR(64) NOT NULL,
    channel_order_no VARCHAR(64),
    status           VARCHAR(16) NOT NULL,
    raw_payload      LONGTEXT,
    created_at       BIGINT      NOT NULL,
    CONSTRAINT uk_channel_receipt UNIQUE (biz_no, status)
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
-- ================= CH-2a/CH-2b/CH-3/CH-4/CH-5 增量 =================
-- 现存 pp_channel 库不会重跑 init（sql.init.mode=never）：新列/新表由 ChannelSchemaUpgrade
-- 启动时幂等补齐（只 ADD COLUMN / CREATE TABLE IF NOT EXISTS，绝不改写既有数据）。

CREATE TABLE IF NOT EXISTS channel_config (
    channel_code      VARCHAR(32) PRIMARY KEY,
    name              VARCHAR(128) NOT NULL,
    adapter_type      VARCHAR(32)  NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    env               VARCHAR(16)  NOT NULL,
    fee_bps           INT,
    weight            INT,
    single_limit      BIGINT,
    daily_limit       BIGINT,
    maintenance_start BIGINT,
    maintenance_end   BIGINT,
    config            LONGTEXT,
    created_at        BIGINT       NOT NULL,
    updated_at        BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS channel_daily_usage (
    channel_code VARCHAR(32) NOT NULL,
    biz_date     VARCHAR(8)  NOT NULL,
    used_amount  BIGINT      NOT NULL,
    used_count   BIGINT      NOT NULL,
    CONSTRAINT uk_channel_daily_usage UNIQUE (channel_code, biz_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS channel_route_rule (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    priority     INT         NOT NULL,
    min_amount   BIGINT,
    max_amount   BIGINT,
    card_bin     VARCHAR(16),
    channel_code VARCHAR(32) NOT NULL,
    weight       INT,
    status       VARCHAR(16) NOT NULL,
    version      INT,
    created_at   BIGINT      NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS channel_bill (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel_code  VARCHAR(32)  NOT NULL,
    biz_date      VARCHAR(8)   NOT NULL,
    file_path     VARCHAR(512),
    total_count   BIGINT,
    total_amount  BIGINT,
    status        VARCHAR(16)  NOT NULL,
    parsed_count  BIGINT,
    parsed_amount BIGINT,
    matched_count BIGINT,
    diff_count    BIGINT,
    dispute_count BIGINT,
    message       VARCHAR(1024),
    created_at    BIGINT       NOT NULL,
    parsed_at     BIGINT,
    CONSTRAINT uk_channel_bill UNIQUE (channel_code, biz_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS channel_bill_detail (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    bill_id          BIGINT      NOT NULL,
    channel_order_no VARCHAR(64) NOT NULL,
    biz_no           VARCHAR(64) NOT NULL,
    type             VARCHAR(16) NOT NULL,
    amount           BIGINT      NOT NULL,
    status           VARCHAR(16) NOT NULL,
    raw              LONGTEXT,
    match_result     VARCHAR(32),
    dispute_id       BIGINT,
    present          INT DEFAULT 1,
    CONSTRAINT uk_channel_bill_detail UNIQUE (bill_id, channel_order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sandbox_order (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel_order_no VARCHAR(64) NOT NULL,
    biz_no           VARCHAR(64) NOT NULL,
    type             VARCHAR(16) NOT NULL,
    amount           BIGINT      NOT NULL,
    requested_amount BIGINT,
    status           VARCHAR(16) NOT NULL,
    fault            VARCHAR(128),
    biz_date         VARCHAR(8),
    due_at           BIGINT,
    created_at       BIGINT      NOT NULL,
    finalized_at     BIGINT,
    receipt_sent     INT DEFAULT 0,
    attempts         INT DEFAULT 0,
    CONSTRAINT uk_sandbox_order_no UNIQUE (channel_order_no),
    CONSTRAINT uk_sandbox_biz UNIQUE (biz_no, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ================= CH-6/X-4 签约代扣（P2 F 泳道，只增不改；docker 由 ChannelSchemaUpgrade 幂等补齐） =================

CREATE TABLE IF NOT EXISTS sign_contract (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    sign_no           VARCHAR(40)  NOT NULL,
    account_id        VARCHAR(40)  NOT NULL,
    merchant_id       VARCHAR(40)  NOT NULL,
    channel_code      VARCHAR(32)  NOT NULL,
    channel_sign_no   VARCHAR(64),
    card_no_hash      VARCHAR(64)  NOT NULL,
    card_no_mask      VARCHAR(32)  NOT NULL,
    card_no_cipher    VARCHAR(512) NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    single_limit      BIGINT,
    period_limit      BIGINT,
    period_type       VARCHAR(8),
    effective_at      BIGINT       NOT NULL,
    expired_at        BIGINT       NOT NULL,
    deduct_day        INT,
    max_retry         INT          NOT NULL,
    retry_interval_ms BIGINT       NOT NULL,
    callback_url      VARCHAR(512),
    app_id            VARCHAR(64),
    request_id        VARCHAR(80)  NOT NULL,
    authorized_scope  LONGTEXT,
    active_key        VARCHAR(160),
    signed_at         BIGINT,
    cancelled_at      BIGINT,
    cancel_reason     VARCHAR(256),
    created_at        BIGINT       NOT NULL,
    updated_at        BIGINT,
    CONSTRAINT uk_sign_no UNIQUE (sign_no),
    CONSTRAINT uk_sign_request UNIQUE (request_id),
    CONSTRAINT uk_sign_active UNIQUE (active_key),
    KEY idx_sign_account (account_id),
    KEY idx_sign_merchant (merchant_id),
    KEY idx_sign_expired (status, expired_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sign_deduct (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    deduct_no          VARCHAR(40) NOT NULL,
    sign_no            VARCHAR(40) NOT NULL,
    account_id         VARCHAR(40),
    merchant_id        VARCHAR(40),
    request_id         VARCHAR(80) NOT NULL,
    attempt_request_id VARCHAR(96),
    order_id           VARCHAR(40),
    payment_id         VARCHAR(40),
    amount             BIGINT      NOT NULL,
    status             VARCHAR(16) NOT NULL,
    attempts           INT         NOT NULL,
    max_retry          INT         NOT NULL,
    next_retry_at      BIGINT,
    fail_reason        VARCHAR(512),
    period_key         VARCHAR(8),
    channel_code       VARCHAR(32),
    created_at         BIGINT      NOT NULL,
    updated_at         BIGINT,
    finished_at        BIGINT,
    CONSTRAINT uk_sign_deduct_no UNIQUE (deduct_no),
    CONSTRAINT uk_sign_deduct_request UNIQUE (request_id),
    KEY idx_sign_deduct_sign (sign_no, period_key, status),
    KEY idx_sign_deduct_retry (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sign_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    event         VARCHAR(32) NOT NULL,
    biz_id        VARCHAR(64) NOT NULL,
    sign_no       VARCHAR(40) NOT NULL,
    deduct_no     VARCHAR(40),
    payload       LONGTEXT,
    status        VARCHAR(16) NOT NULL,
    attempts      INT         NOT NULL,
    notify_id     VARCHAR(40),
    last_error    VARCHAR(512),
    created_at    BIGINT      NOT NULL,
    sent_at       BIGINT,
    next_retry_at BIGINT,
    CONSTRAINT uk_sign_event UNIQUE (event, biz_id),
    KEY idx_sign_event_dispatch (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sign_audit (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sign_no    VARCHAR(40),
    deduct_no  VARCHAR(40),
    action     VARCHAR(32) NOT NULL,
    operator   VARCHAR(64),
    request_id VARCHAR(96),
    amount     BIGINT,
    result     VARCHAR(16),
    reason     VARCHAR(512),
    created_at BIGINT      NOT NULL,
    KEY idx_sign_audit_sign (sign_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
