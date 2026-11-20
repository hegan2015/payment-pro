CREATE TABLE IF NOT EXISTS channel_trade (
    biz_no      VARCHAR(64) PRIMARY KEY,
    type        VARCHAR(16) NOT NULL,
    orig_biz_no VARCHAR(64),
    card_no     VARCHAR(32) NOT NULL,
    amount      BIGINT      NOT NULL,
    status      VARCHAR(16) NOT NULL,
    reason      VARCHAR(256),
    created_at  BIGINT      NOT NULL
);

-- CH-1 渠道异步回执 + 查询补单：渠道方订单号 / 异步受理时间 / 终态回执时间。
-- status 枚举扩展：PREAUTH（同步预授权）/ SUBMITTED（异步受理）/ CAPTURED / REFUNDED / VOIDED / FAILED。
ALTER TABLE channel_trade ADD COLUMN IF NOT EXISTS channel_order_no VARCHAR(64);
ALTER TABLE channel_trade ADD COLUMN IF NOT EXISTS submitted_at BIGINT;
ALTER TABLE channel_trade ADD COLUMN IF NOT EXISTS receipt_at BIGINT;

-- 回执乱序/重复去重：UNIQUE(biz_no,status)，终态回执只留一条。
CREATE TABLE IF NOT EXISTS channel_receipt_log (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    biz_no           VARCHAR(64) NOT NULL,
    channel_order_no VARCHAR(64),
    status           VARCHAR(16) NOT NULL,
    raw_payload      CLOB,
    created_at       BIGINT      NOT NULL,
    CONSTRAINT uk_channel_receipt UNIQUE (biz_no, status)
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
-- ================= CH-2a/CH-2b/CH-3/CH-4/CH-5 增量（只增不改，绝不改写既有行） =================

-- CH-2a：受理渠道落痕（路由决策可观测；历史行为 NULL，归属模拟银行主渠道）
ALTER TABLE channel_trade ADD COLUMN IF NOT EXISTS channel_code VARCHAR(32);

-- CH-2a/CH-5：渠道配置（适配器/环境/状态/成本/权重/限额/维护窗口）
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
    config            CLOB,
    created_at        BIGINT       NOT NULL,
    updated_at        BIGINT
);

-- CH-5：渠道日累计（原子 incr，按 biz_date 分行 → 跨日自然重置）
CREATE TABLE IF NOT EXISTS channel_daily_usage (
    channel_code VARCHAR(32) NOT NULL,
    biz_date     VARCHAR(8)  NOT NULL,
    used_amount  BIGINT      NOT NULL,
    used_count   BIGINT      NOT NULL,
    CONSTRAINT uk_channel_daily_usage UNIQUE (channel_code, biz_date)
);

-- CH-3：路由规则（结构化条件：金额区间 + 卡 BIN → 权重覆盖，版本化可回滚）
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
);

-- CH-4：对账文件主表（幂等键 channel_code + biz_date）
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
);

-- CH-4：对账明细（幂等键 bill_id + channel_order_no；present=0 标记最新账单已移除的历史行，不删行）
CREATE TABLE IF NOT EXISTS channel_bill_detail (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    bill_id          BIGINT      NOT NULL,
    channel_order_no VARCHAR(64) NOT NULL,
    biz_no           VARCHAR(64) NOT NULL,
    type             VARCHAR(16) NOT NULL,
    amount           BIGINT      NOT NULL,
    status           VARCHAR(16) NOT NULL,
    raw              CLOB,
    match_result     VARCHAR(32),
    dispute_id       BIGINT,
    present          INT DEFAULT 1,
    CONSTRAINT uk_channel_bill_detail UNIQUE (bill_id, channel_order_no)
);

-- CH-2b：沙箱银行「渠道侧」自有账本（模拟外部银行，与平台 channel_trade 分离）
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
);

-- ================= CH-6/X-4 签约代扣（P2 F 泳道，只增不改，绝不改写既有行） =================
-- 域归属决策：签约/代扣落 channel 域（与 channel_trade 同库同事务边界，复用 ChannelAdapter SPI，
-- 不新增进程 ⇒ 13/13 健康列表硬断言不破）。卡号明文绝不落库：hash + mask + AES-GCM 密文。

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
    authorized_scope  CLOB,
    -- 有效协议唯一键：SIGNED 时 = accountId:merchantId:channelCode，解约/到期置 NULL
    -- ⇒ 同三元组最多一份有效协议（并发签约结构性去重；NULL 可重复，历史终态行不受限）
    active_key        VARCHAR(160),
    signed_at         BIGINT,
    cancelled_at      BIGINT,
    cancel_reason     VARCHAR(256),
    created_at        BIGINT       NOT NULL,
    updated_at        BIGINT,
    CONSTRAINT uk_sign_no UNIQUE (sign_no),
    CONSTRAINT uk_sign_request UNIQUE (request_id),
    CONSTRAINT uk_sign_active UNIQUE (active_key)
);
CREATE INDEX IF NOT EXISTS idx_sign_account ON sign_contract(account_id);
CREATE INDEX IF NOT EXISTS idx_sign_merchant ON sign_contract(merchant_id);
CREATE INDEX IF NOT EXISTS idx_sign_expired ON sign_contract(status, expired_at);

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
    CONSTRAINT uk_sign_deduct_request UNIQUE (request_id)
);
CREATE INDEX IF NOT EXISTS idx_sign_deduct_sign ON sign_deduct(sign_no, period_key, status);
CREATE INDEX IF NOT EXISTS idx_sign_deduct_retry ON sign_deduct(status, next_retry_at);

-- 事件事务 outbox：与协议状态变更同一本地事务落库，提交后经 NotifyFacade.send 投递
CREATE TABLE IF NOT EXISTS sign_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    event         VARCHAR(32) NOT NULL,
    biz_id        VARCHAR(64) NOT NULL,
    sign_no       VARCHAR(40) NOT NULL,
    deduct_no     VARCHAR(40),
    payload       CLOB,
    status        VARCHAR(16) NOT NULL,
    attempts      INT         NOT NULL,
    notify_id     VARCHAR(40),
    last_error    VARCHAR(512),
    created_at    BIGINT      NOT NULL,
    sent_at       BIGINT,
    next_retry_at BIGINT,
    CONSTRAINT uk_sign_event UNIQUE (event, biz_id)
);
CREATE INDEX IF NOT EXISTS idx_sign_event_dispatch ON sign_event(status, next_retry_at);

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
    created_at BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sign_audit_sign ON sign_audit(sign_no, id);
