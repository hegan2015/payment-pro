package com.qoder.payment.dtx;

/** 建表 DDL。协调器表落支付核心库，参与者表落各参与域库。 */
public final class DtxSchema {

    public static final String COORDINATOR = """
            CREATE TABLE IF NOT EXISTS dtx_transaction (
                xid           VARCHAR(64) PRIMARY KEY,
                biz_type      VARCHAR(32) NOT NULL,
                biz_no        VARCHAR(64) NOT NULL,
                status        VARCHAR(16) NOT NULL,
                fail_reason   VARCHAR(256),
                retry_count   INT         NOT NULL DEFAULT 0,
                next_retry_at BIGINT      NOT NULL DEFAULT 0,
                version       INT         NOT NULL DEFAULT 0,
                created_at    BIGINT      NOT NULL,
                updated_at    BIGINT      NOT NULL,
                CONSTRAINT uk_dtx_biz UNIQUE (biz_type, biz_no)
            );
            CREATE TABLE IF NOT EXISTS dtx_branch (
                id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                xid         VARCHAR(64) NOT NULL,
                branch_id   VARCHAR(80) NOT NULL,
                participant VARCHAR(32) NOT NULL,
                action      VARCHAR(32) NOT NULL,
                status      VARCHAR(16) NOT NULL,
                context     CLOB,
                seq         INT         NOT NULL,
                CONSTRAINT uk_dtx_branch UNIQUE (xid, branch_id)
            );
            CREATE INDEX IF NOT EXISTS idx_dtx_recover ON dtx_transaction (status, next_retry_at);
            """;

    public static final String PARTICIPANT = """
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
            """;

    public static final String IDEMPOTENT = """
            CREATE TABLE IF NOT EXISTS idempotent_record (
                biz_type   VARCHAR(32) NOT NULL,
                biz_no     VARCHAR(64) NOT NULL,
                result     CLOB,
                created_at BIGINT      NOT NULL,
                PRIMARY KEY (biz_type, biz_no)
            );
            """;

    private DtxSchema() {
    }
}