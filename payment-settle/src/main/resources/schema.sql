CREATE SEQUENCE IF NOT EXISTS seq_settle START WITH 1;
CREATE ALIAS IF NOT EXISTS next_value_for FOR "com.qoder.payment.common.db.H2Sequence.next";

CREATE TABLE IF NOT EXISTS settle_record (
    id          VARCHAR(32) PRIMARY KEY,
    type        VARCHAR(16) NOT NULL,
    biz_id      VARCHAR(64) NOT NULL,
    payment_id  VARCHAR(32) NOT NULL,
    order_id    VARCHAR(32),
    merchant_id VARCHAR(32) NOT NULL,
    gross       BIGINT      NOT NULL,
    fee         BIGINT      NOT NULL,
    net         BIGINT      NOT NULL,
    method      VARCHAR(16),
    status      VARCHAR(16) NOT NULL,
    created_at  BIGINT      NOT NULL,
    CONSTRAINT uk_settle_biz UNIQUE (biz_id, type)
);
CREATE INDEX IF NOT EXISTS idx_settle_merchant ON settle_record (merchant_id);

CREATE TABLE IF NOT EXISTS idempotent_record (
    biz_type   VARCHAR(32) NOT NULL,
    biz_no     VARCHAR(64) NOT NULL,
    result     CLOB,
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (biz_type, biz_no)
);

-- ============================================================================
-- P1 第二期 ST-1~ST-5 清结算工作流新表（settle_cycle / settle_batch / settle_batch_item /
-- fee_rule / fee_rule_audit / settle_share_detail / withdraw_order / merchant_bill / shedlock）
-- 的 DDL 单一事实来源在 com.qoder.payment.settle.config.SettleSchema（幂等、只增不改，
-- H2/MySQL 双方言），由 SettleSchemaUpgrade（local/未分片）与 SettleShardPreBootstrap
-- （docker 分片态，早于 ShardingSphere DataSource）两个入口执行。
-- 原因：docker sql.init.mode=never 不重跑本脚本，且分片 SINGLE 规则要求新表先于元数据加载存在。
-- 归属：全部单库不分片（状态机/协调器表含 UPDATE，影子写只 INSERT；withdraw_order.request_id
-- 与 settle_share_detail(share_id,receiver) 是全局幂等键，分片后唯一键无法跨片保证）。
-- settle_record 保持原样不 ALTER（不可变 + 影子副本逐列一致 ⇒ 分片回滚后逐条一致）。
-- ============================================================================