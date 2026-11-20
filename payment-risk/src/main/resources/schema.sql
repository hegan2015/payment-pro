-- payment-risk · local H2（data/risk）。R-1~R-4 风控工作流持久化（E-3 前置：risk 域新增库）。
-- 只 CREATE IF NOT EXISTS，additive，绝不 DROP/TRUNCATE/DELETE 既有行。
CREATE SEQUENCE IF NOT EXISTS seq_risk_snapshot START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_risk_decision START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_risk_alert START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_risk_review START WITH 1;
CREATE ALIAS IF NOT EXISTS next_value_for FOR "com.qoder.payment.common.db.H2Sequence.next";

-- R-1/R-2/R-3/R-4 配置快照版本（RULES/QUOTAS/LISTS/THRESHOLDS 统一版本化 + 审计 + 回滚依据）
CREATE TABLE IF NOT EXISTS risk_config_snapshot (
    id          VARCHAR(32) PRIMARY KEY,
    kind        VARCHAR(16)  NOT NULL,
    version     INT          NOT NULL,
    payload     CLOB         NOT NULL,
    source      VARCHAR(16)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    operator    VARCHAR(64),
    change_note VARCHAR(512),
    created_at  BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_risk_snapshot_kind ON risk_config_snapshot (kind, version);

-- R-1/R-4 处置留痕（可解释：命中规则 + 因子明细 + traceId）
CREATE TABLE IF NOT EXISTS risk_decision_log (
    id           VARCHAR(32) PRIMARY KEY,
    payer_id     VARCHAR(64),
    merchant_id  VARCHAR(64),
    amount       BIGINT,
    scene        VARCHAR(16),
    method       VARCHAR(16),
    channel_code VARCHAR(32),
    card_mask    VARCHAR(32),
    device_id    VARCHAR(64),
    ip           VARCHAR(64),
    score        INT,
    action       VARCHAR(16),
    passed       BOOLEAN,
    reason       VARCHAR(256),
    code         VARCHAR(32),
    hit_rules    CLOB,
    factors      CLOB,
    shadow       BOOLEAN,
    degraded     BOOLEAN,
    rule_version INT,
    list_hit     VARCHAR(16),
    quota_hit    VARCHAR(128),
    trace_id     VARCHAR(64),
    biz_no       VARCHAR(64),
    dispute_id   BIGINT,
    created_at   BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_risk_decision_payer ON risk_decision_log (payer_id, created_at);

-- §5.4 误配置护栏告警（规则编译失败/执行异常/超时 → 放行并告警）
CREATE TABLE IF NOT EXISTS risk_alert (
    id         VARCHAR(32) PRIMARY KEY,
    kind       VARCHAR(32)  NOT NULL,
    rule_id    VARCHAR(64),
    message    VARCHAR(1024),
    trace_id   VARCHAR(64),
    created_at BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_risk_alert_time ON risk_alert (created_at);

-- R-4 转人工工单（与差错平台 DisputeFacade 单据双向关联）
CREATE TABLE IF NOT EXISTS risk_review (
    id          VARCHAR(32) PRIMARY KEY,
    decision_id BIGINT      NOT NULL,
    payer_id    VARCHAR(64),
    amount      BIGINT,
    status      VARCHAR(16) NOT NULL,
    reviewer    VARCHAR(64),
    dispute_id  BIGINT,
    created_at  BIGINT      NOT NULL,
    updated_at  BIGINT      NOT NULL
);

-- R-2 限额用量镜像（计数权威在内存/Redis，本表供审计与跨重启观测；仅有配额规则时写入）
CREATE TABLE IF NOT EXISTS quota_usage (
    quota_key   VARCHAR(128) NOT NULL,
    period_key  VARCHAR(32)  NOT NULL,
    used_amount BIGINT       NOT NULL DEFAULT 0,
    used_count  INT          NOT NULL DEFAULT 0,
    updated_at  BIGINT       NOT NULL,
    PRIMARY KEY (quota_key, period_key)
);