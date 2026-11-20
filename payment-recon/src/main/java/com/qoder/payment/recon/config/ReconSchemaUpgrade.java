package com.qoder.payment.recon.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 对账库增量升级（幂等、<b>只增不改</b>，双 profile 同构）——RC-2/RC-3/RC-4/RC-5。
 *
 * <ul>
 *   <li>新表 recon_batch / recon_progress / channel_recon_run / channel_recon_diff
 *       （全部<b>不分片</b>，SINGLE 规则钉在主库，与 dispute 表同口径）；</li>
 *   <li>recon_report 增 biz_date / recon_type / channel_code（RC-2 按会计日落报告）。</li>
 * </ul>
 *
 * <p><b>分片红线</b>：recon_report 是按 id 分 4 片的逻辑表，新增列必须同时补到
 * {@code recon_report_0..N-1} 物理表与原单表——否则 ShardMirror 的整行双写会因列缺失失败。
 * 本升级直连原单库（{@code ShardRawAccess.raw}）逐表 ALTER，原表原行全部保留，
 * 绝不 DROP/TRUNCATE/DELETE/UPDATE 既有业务数据。
 *
 * <p>local：schema.sql 已建好，本升级为空操作；docker：sql.init.mode=never，
 * 现存 pp_recon 由本升级在 Bean 阶段补齐（早于任何对账写入）。失败即启动失败。
 */
@Slf4j
@Component("reconSchemaUpgrade")
public class ReconSchemaUpgrade implements InitializingBean {

    private final JdbcTemplate jdbc;
    private final Environment env;

    public ReconSchemaUpgrade(JdbcTemplate jdbc, Environment env) {
        this.jdbc = jdbc;
        this.env = env;
    }

    @Override
    public void afterPropertiesSet() {
        JdbcTemplate raw = com.qoder.payment.common.db.ShardRawAccess.raw(this.jdbc);
        String product = raw.execute((ConnectionCallback<String>) con -> con.getMetaData().getDatabaseProductName());
        boolean h2 = product != null && product.toUpperCase(Locale.ROOT).contains("H2");

        if (h2) {
            raw.execute("CREATE SEQUENCE IF NOT EXISTS seq_recon_batch START WITH 1");
            raw.execute("CREATE SEQUENCE IF NOT EXISTS seq_channel_recon START WITH 1");
            raw.execute("CREATE SEQUENCE IF NOT EXISTS seq_channel_diff START WITH 1");
        } else {
            raw.update("INSERT IGNORE INTO sequences(name, value) VALUES ('seq_recon_batch', 0)");
            raw.update("INSERT IGNORE INTO sequences(name, value) VALUES ('seq_channel_recon', 0)");
            raw.update("INSERT IGNORE INTO sequences(name, value) VALUES ('seq_channel_diff', 0)");
        }

        // ---------------- RC-2/RC-3 批次 + 进度（不分片） ----------------
        raw.execute("CREATE TABLE IF NOT EXISTS recon_batch ("
                + "id VARCHAR(32) PRIMARY KEY,"
                + "biz_date BIGINT NOT NULL,"
                + "recon_type VARCHAR(16) NOT NULL,"
                + "channel_code VARCHAR(32) NOT NULL DEFAULT '',"
                + "shard_index INT NOT NULL DEFAULT 0,"
                + "shard_total INT NOT NULL DEFAULT 1,"
                + "status VARCHAR(16) NOT NULL,"
                + "attempt INT NOT NULL DEFAULT 1,"
                + "report_id VARCHAR(32),"
                + "total INT NOT NULL DEFAULT 0,"
                + "checked INT NOT NULL DEFAULT 0,"
                + "passed INT NOT NULL DEFAULT 0,"
                + "failed INT NOT NULL DEFAULT 0,"
                + "dispute_count INT NOT NULL DEFAULT 0,"
                + "message VARCHAR(512),"
                + "created_at BIGINT NOT NULL,"
                + "updated_at BIGINT NOT NULL,"
                + "CONSTRAINT uk_recon_batch UNIQUE (biz_date, recon_type, channel_code, shard_index, shard_total))"
                + engine(h2));
        raw.execute("CREATE TABLE IF NOT EXISTS recon_progress ("
                + "biz_date BIGINT NOT NULL,"
                + "recon_type VARCHAR(16) NOT NULL,"
                + "shard_index INT NOT NULL DEFAULT 0,"
                + "shard_total INT NOT NULL DEFAULT 1,"
                + "total BIGINT NOT NULL DEFAULT 0,"
                + "checked BIGINT NOT NULL DEFAULT 0,"
                + "passed INT NOT NULL DEFAULT 0,"
                + "failed INT NOT NULL DEFAULT 0,"
                + "dispute_count BIGINT NOT NULL DEFAULT 0,"
                + "status VARCHAR(16) NOT NULL,"
                + "updated_at BIGINT NOT NULL,"
                + "PRIMARY KEY (biz_date, recon_type, shard_index, shard_total))" + engine(h2));

        // ---------------- RC-3 渠道内外对账（不分片） ----------------
        raw.execute("CREATE TABLE IF NOT EXISTS channel_recon_run ("
                + "id VARCHAR(32) PRIMARY KEY,"
                + "channel_code VARCHAR(32) NOT NULL,"
                + "biz_date BIGINT NOT NULL,"
                + "source VARCHAR(16) NOT NULL,"
                + "bill_id BIGINT,"
                + "internal_count BIGINT NOT NULL DEFAULT 0,"
                + "external_count BIGINT NOT NULL DEFAULT 0,"
                + "matched_count BIGINT NOT NULL DEFAULT 0,"
                + "long_count BIGINT NOT NULL DEFAULT 0,"
                + "short_count BIGINT NOT NULL DEFAULT 0,"
                + "amount_count BIGINT NOT NULL DEFAULT 0,"
                + "status_count BIGINT NOT NULL DEFAULT 0,"
                + "fee_count BIGINT NOT NULL DEFAULT 0,"
                + "internal_total BIGINT NOT NULL DEFAULT 0,"
                + "external_total BIGINT NOT NULL DEFAULT 0,"
                + "net_diff BIGINT NOT NULL DEFAULT 0,"
                + "pass BOOLEAN NOT NULL DEFAULT FALSE,"
                + "dispute_count BIGINT NOT NULL DEFAULT 0,"
                + "fee_note VARCHAR(512),"
                + "message VARCHAR(512),"
                + "created_at BIGINT NOT NULL,"
                + "updated_at BIGINT NOT NULL,"
                + "CONSTRAINT uk_channel_recon_run UNIQUE (channel_code, biz_date))" + engine(h2));
        raw.execute("CREATE TABLE IF NOT EXISTS channel_recon_diff ("
                + "id BIGINT PRIMARY KEY,"
                + "channel_code VARCHAR(32) NOT NULL,"
                + "biz_date BIGINT NOT NULL,"
                + "biz_no VARCHAR(64) NOT NULL,"
                + "channel_order_no VARCHAR(64),"
                + "diff_type VARCHAR(16) NOT NULL,"
                + "internal_amount BIGINT,"
                + "external_amount BIGINT,"
                + "internal_fee BIGINT,"
                + "external_fee BIGINT,"
                + "internal_status VARCHAR(16),"
                + "external_status VARCHAR(16),"
                + "status VARCHAR(16) NOT NULL,"
                + "dispute_id BIGINT,"
                + "suggestion VARCHAR(512),"
                + "created_at BIGINT NOT NULL,"
                + "updated_at BIGINT NOT NULL,"
                + "CONSTRAINT uk_channel_recon_diff UNIQUE (channel_code, biz_date, biz_no, diff_type))" + engine(h2));

        // ---------------- RC-2 recon_report additive 列（含分片物理表与原单表） ----------------
        addColumn(raw, h2, "recon_report", "biz_date", "BIGINT");
        addColumn(raw, h2, "recon_report", "recon_type", "VARCHAR(16)");
        addColumn(raw, h2, "recon_report", "channel_code", "VARCHAR(32)");
        int shardCount = env.getProperty("payment.sharding.shard-count", Integer.class, 4);
        boolean sharding = Boolean.parseBoolean(env.getProperty("payment.sharding.enabled", "false"));
        int physicalAltered = 0;
        if (sharding && !h2) {
            for (int k = 0; k < shardCount; k++) {
                String physical = "recon_report_" + k;
                if (!tableExists(raw, physical)) {
                    continue;
                }
                addColumn(raw, false, physical, "biz_date", "BIGINT");
                addColumn(raw, false, physical, "recon_type", "VARCHAR(16)");
                addColumn(raw, false, physical, "channel_code", "VARCHAR(32)");
                physicalAltered++;
            }
        }
        addIndex(raw, h2, "recon_report", "idx_recon_report_day", "biz_date");
        log.info("对账库增量校验完成 product={} reconBatch=ready reconProgress=ready channelRecon=ready "
                        + "reconReportCols=biz_date/recon_type/channel_code physicalShardTablesAltered={}",
                product, physicalAltered);
    }

    /** 供端点/验证脚本读取：本域新增表清单（全部不分片）。 */
    public static List<String> newSingleTables() {
        return List.of("recon_batch", "recon_progress", "channel_recon_run", "channel_recon_diff");
    }

    // ------------------------------------------------------------------ 工具

    private static String engine(boolean h2) {
        return h2 ? "" : " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    }

    private void addColumn(JdbcTemplate raw, boolean h2, String table, String column, String type) {
        if (!tableExists(raw, table)) {
            return;
        }
        if (h2) {
            raw.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + column + " " + type);
            return;
        }
        if (columnExists(raw, table, column)) {
            return;
        }
        raw.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        log.info("对账库 additive 加列 table={} column={} type={}", table, column, type);
    }

    private void addIndex(JdbcTemplate raw, boolean h2, String table, String index, String column) {
        if (!tableExists(raw, table)) {
            return;
        }
        if (h2) {
            raw.execute("CREATE INDEX IF NOT EXISTS " + index + " ON " + table + " (" + column + ")");
            return;
        }
        Integer found = raw.queryForObject("SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name=? AND index_name=?", Integer.class, table, index);
        if (found != null && found > 0) {
            return;
        }
        raw.execute("CREATE INDEX " + index + " ON " + table + " (" + column + ")");
    }

    private boolean tableExists(JdbcTemplate raw, String table) {
        try {
            Integer found = raw.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE() AND table_name=?", Integer.class, table);
            return found != null && found > 0;
        } catch (Exception e) {
            try {
                raw.queryForList("SELECT 1 FROM " + table + " WHERE 1=0");
                return true;
            } catch (Exception absent) {
                return false;
            }
        }
    }

    private boolean columnExists(JdbcTemplate raw, String table, String column) {
        Integer found = raw.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name=? AND column_name=?", Integer.class, table, column);
        return found != null && found > 0;
    }
}