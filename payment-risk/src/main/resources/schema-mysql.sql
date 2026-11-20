-- payment-risk · MySQL（pp_risk）。对空库执行一次；docker profile sql.init.mode=never。
-- 只 CREATE IF NOT EXISTS / INSERT IGNORE，additive，绝不 DROP/TRUNCATE/DELETE 既有行。
CREATE TABLE IF NOT EXISTS sequences (
    name  VARCHAR(64) PRIMARY KEY,
    value BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_risk_snapshot', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_risk_decision', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_risk_alert', 0);
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_risk_review', 0);
DROP FUNCTION IF EXISTS next_value_for;
DELIMITER //
CREATE FUNCTION next_value_for(s VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) RETURNS BIGINT
MODIFIES SQL DATA
BEGIN
    UPDATE sequences SET value = LAST_INSERT_ID(value + 1) WHERE name = s;
    RETURN LAST_INSERT_ID();
END//
DELIMITER ;

CREATE TABLE IF NOT EXISTS risk_config_snapshot (
    id          VARCHAR(32) PRIMARY KEY,
    kind        VARCHAR(16)  NOT NULL,
    version     INT          NOT NULL,
    payload     LONGTEXT     NOT NULL,
    source      VARCHAR(16)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    operator    VARCHAR(64),
    change_note VARCHAR(512),
    created_at  BIGINT       NOT NULL,
    INDEX idx_risk_snapshot_kind (kind, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
    passed       TINYINT(1),
    reason       VARCHAR(256),
    code         VARCHAR(32),
    hit_rules    LONGTEXT,
    factors      LONGTEXT,
    shadow       TINYINT(1),
    degraded     TINYINT(1),
    rule_version INT,
    list_hit     VARCHAR(16),
    quota_hit    VARCHAR(128),
    trace_id     VARCHAR(64),
    biz_no       VARCHAR(64),
    dispute_id   BIGINT,
    created_at   BIGINT      NOT NULL,
    INDEX idx_risk_decision_payer (payer_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS risk_alert (
    id         VARCHAR(32) PRIMARY KEY,
    kind       VARCHAR(32)  NOT NULL,
    rule_id    VARCHAR(64),
    message    VARCHAR(1024),
    trace_id   VARCHAR(64),
    created_at BIGINT       NOT NULL,
    INDEX idx_risk_alert_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS quota_usage (
    quota_key   VARCHAR(128) NOT NULL,
    period_key  VARCHAR(32)  NOT NULL,
    used_amount BIGINT       NOT NULL DEFAULT 0,
    used_count  INT          NOT NULL DEFAULT 0,
    updated_at  BIGINT       NOT NULL,
    PRIMARY KEY (quota_key, period_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;