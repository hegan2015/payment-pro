CREATE SEQUENCE IF NOT EXISTS seq_notify START WITH 1;
CREATE ALIAS IF NOT EXISTS next_value_for FOR "com.qoder.payment.common.db.H2Sequence.next";

CREATE TABLE IF NOT EXISTS notify_record (
    id            VARCHAR(32) PRIMARY KEY,
    event         VARCHAR(32)  NOT NULL,
    biz_id        VARCHAR(64)  NOT NULL,
    payload       CLOB         NOT NULL,
    callback_url  VARCHAR(256),
    status        VARCHAR(16)  NOT NULL,
    attempts      BIGINT       NOT NULL DEFAULT 0,
    next_retry_at BIGINT       NOT NULL DEFAULT 0,
    last_error    VARCHAR(256),
    created_at    BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_notify_biz ON notify_record (biz_id);
CREATE INDEX IF NOT EXISTS idx_notify_pending ON notify_record (status, next_retry_at);

-- S-3/N-1 通知签名：商户 appId（取密钥标识）+ 签名三要素（sign_type/signature/nonce）。
ALTER TABLE notify_record ADD COLUMN IF NOT EXISTS app_id VARCHAR(64);
ALTER TABLE notify_record ADD COLUMN IF NOT EXISTS sign_type VARCHAR(16) NOT NULL DEFAULT 'HMAC_SHA256';
ALTER TABLE notify_record ADD COLUMN IF NOT EXISTS signature VARCHAR(512);
ALTER TABLE notify_record ADD COLUMN IF NOT EXISTS nonce VARCHAR(64);
-- N-4 通知重发计数（additive，只增列不改既有行）。
ALTER TABLE notify_record ADD COLUMN IF NOT EXISTS resend_count INT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_notify_app_created ON notify_record (app_id, created_at);

CREATE TABLE IF NOT EXISTS idempotent_record (
    biz_type   VARCHAR(32) NOT NULL,
    biz_no     VARCHAR(64) NOT NULL,
    result     CLOB,
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (biz_type, biz_no)
);
-- I-3 分布式调度 ShedLock 锁表（BIGINT 毫秒，与 MySQL 同构；作业名全局唯一，多实例互斥）。
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until BIGINT       NOT NULL,
    locked_at  BIGINT       NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
