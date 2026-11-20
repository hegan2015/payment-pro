-- payment-notify · MySQL（pp_notify）。对空库执行一次；docker profile sql.init.mode=never。
CREATE TABLE IF NOT EXISTS sequences (
    name  VARCHAR(64) PRIMARY KEY,
    value BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO sequences(name, value) VALUES ('seq_notify', 0);
DROP FUNCTION IF EXISTS next_value_for;
DELIMITER //
CREATE FUNCTION next_value_for(s VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) RETURNS BIGINT
MODIFIES SQL DATA
BEGIN
    UPDATE sequences SET value = LAST_INSERT_ID(value + 1) WHERE name = s;
    RETURN LAST_INSERT_ID();
END//
DELIMITER ;

CREATE TABLE IF NOT EXISTS notify_record (
    id            VARCHAR(32) PRIMARY KEY,
    event         VARCHAR(32)  NOT NULL,
    biz_id        VARCHAR(64)  NOT NULL,
    payload       LONGTEXT     NOT NULL,
    callback_url  VARCHAR(256),
    status        VARCHAR(16)  NOT NULL,
    attempts      BIGINT       NOT NULL DEFAULT 0,
    next_retry_at BIGINT       NOT NULL DEFAULT 0,
    last_error    VARCHAR(256),
    created_at    BIGINT       NOT NULL,
    app_id        VARCHAR(64),
    sign_type     VARCHAR(16)  NOT NULL DEFAULT 'HMAC_SHA256',
    signature     VARCHAR(512),
    nonce         VARCHAR(64),
    resend_count  INT          NOT NULL DEFAULT 0,
    INDEX idx_notify_biz (biz_id),
    INDEX idx_notify_pending (status, next_retry_at),
    INDEX idx_notify_app_created (app_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS idempotent_record (
    biz_type   VARCHAR(32) NOT NULL,
    biz_no     VARCHAR(64) NOT NULL,
    result     LONGTEXT,
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (biz_type, biz_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- I-3 分布式调度 ShedLock 锁表（BIGINT 毫秒）。docker 现存库由 ShedLockSchemaInitializer 启动时幂等补建。
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until BIGINT       NOT NULL,
    locked_at  BIGINT       NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
