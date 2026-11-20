package com.qoder.payment.accounting.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 账务库增量升级（幂等、<b>只增不改</b>，双 profile 同构）。
 *
 * <p>S-7：reserve_check_log / reserve_bank_adjust 补建。
 * <p>P1-E（AC-2/AC-3/AC-5）：
 * <ul>
 *   <li>新表 accounting_subject / account_subject_map / subject_balance / accounting_day /
 *       day_end_report / accounting_batch_run / accounting_day_snapshot（全部<b>不分片</b>）；</li>
 *   <li>ledger_entry 增 subject_code / biz_date；transfer_record 增 biz_date；
 *       reserve_check_log 增 biz_date/channel_code/level/direction/dispute_no；
 *       reserve_bank_adjust 增 channel_code。</li>
 * </ul>
 *
 * <p><b>分片红线</b>：ledger_entry 是按 account_id 分片的逻辑表，新增列必须同时补到
 * {@code ledger_entry_0..N-1} 物理表——否则 {@code ShardRowMirror} 的整行双写（SELECT * → INSERT）
 * 会因列缺失失败。本升级直连原单库（{@code ShardRawAccess.raw}）逐表 ALTER，
 * 原表原行全部保留，绝不 DROP/TRUNCATE/DELETE/UPDATE 既有业务数据。
 *
 * <p>local：schema.sql 已建好，本升级为空操作；docker：sql.init.mode=never，现存 pp_accounting
 * 由本升级在 Bean 阶段补齐（早于科目播种与任何过账）。失败即启动失败。
 */
@Slf4j
@Component("accountingSchemaUpgrade")
public class AccountingSchemaUpgrade implements InitializingBean {

    private final JdbcTemplate jdbc;
    private final Environment env;

    public AccountingSchemaUpgrade(JdbcTemplate jdbc, Environment env) {
        this.jdbc = jdbc;
        this.env = env;
    }

    @Override
    public void afterPropertiesSet() {
        // I-5b：结构升级直连原单库（分片态下 DDL 只会广播到分片表，原单表会缺表/缺列）。
        JdbcTemplate raw = com.qoder.payment.common.db.ShardRawAccess.raw(this.jdbc);
        String product = raw.execute((ConnectionCallback<String>) con -> con.getMetaData().getDatabaseProductName());
        boolean h2 = product != null && product.toUpperCase(Locale.ROOT).contains("H2");

        // ---------------- S-7 既有补建（口径不变） ----------------
        if (h2) {
            raw.execute("CREATE SEQUENCE IF NOT EXISTS seq_reserve START WITH 1");
            raw.execute("CREATE SEQUENCE IF NOT EXISTS seq_subject START WITH 1");
        } else {
            raw.update("INSERT IGNORE INTO sequences(name, value) VALUES ('seq_reserve', 0)");
            raw.update("INSERT IGNORE INTO sequences(name, value) VALUES ('seq_subject', 0)");
        }
        raw.execute("CREATE TABLE IF NOT EXISTS reserve_check_log ("
                + "id VARCHAR(32) PRIMARY KEY,"
                + "book_balance BIGINT NOT NULL,"
                + "actual_balance BIGINT NOT NULL,"
                + "diff BIGINT NOT NULL,"
                + "pass BOOLEAN NOT NULL,"
                + "checked_at BIGINT NOT NULL)" + engine(h2));
        raw.execute("CREATE TABLE IF NOT EXISTS reserve_bank_adjust ("
                + "id VARCHAR(32) PRIMARY KEY,"
                + "amount BIGINT NOT NULL,"
                + "reason VARCHAR(256),"
                + "created_at BIGINT NOT NULL)" + engine(h2));

        // ---------------- AC-5 账实核对新维度（additive 列） ----------------
        addColumn(raw, h2, "reserve_check_log", "biz_date", "BIGINT");
        addColumn(raw, h2, "reserve_check_log", "channel_code", "VARCHAR(32)");
        addColumn(raw, h2, "reserve_check_log", "level", "VARCHAR(16)");
        addColumn(raw, h2, "reserve_check_log", "direction", "VARCHAR(16)");
        addColumn(raw, h2, "reserve_check_log", "dispute_no", "VARCHAR(64)");
        addColumn(raw, h2, "reserve_bank_adjust", "channel_code", "VARCHAR(32)");

        // ---------------- AC-2 科目体系（不分片） ----------------
        raw.execute("CREATE TABLE IF NOT EXISTS accounting_subject ("
                + "subject_code VARCHAR(32) PRIMARY KEY,"
                + "name VARCHAR(64) NOT NULL,"
                + "type VARCHAR(16) NOT NULL,"
                + "direction VARCHAR(1) NOT NULL,"
                + "parent_code VARCHAR(32),"
                + "subject_level INT NOT NULL DEFAULT 2,"
                + "system_account VARCHAR(32),"
                + "system_flag BOOLEAN NOT NULL DEFAULT FALSE,"
                + "status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',"
                + "created_at BIGINT NOT NULL)" + engine(h2));
        raw.execute("CREATE TABLE IF NOT EXISTS account_subject_map ("
                + "account_id VARCHAR(32) PRIMARY KEY,"
                + "subject_code VARCHAR(32) NOT NULL,"
                + "created_at BIGINT NOT NULL)" + engine(h2));
        raw.execute("CREATE TABLE IF NOT EXISTS subject_balance ("
                + "subject_code VARCHAR(32) NOT NULL,"
                + "biz_date BIGINT NOT NULL,"
                + "opening_balance BIGINT NOT NULL DEFAULT 0,"
                + "debit_total BIGINT NOT NULL DEFAULT 0,"
                + "credit_total BIGINT NOT NULL DEFAULT 0,"
                + "balance BIGINT NOT NULL DEFAULT 0,"
                + "entry_count INT NOT NULL DEFAULT 0,"
                + "account_count INT NOT NULL DEFAULT 0,"
                + "updated_at BIGINT NOT NULL,"
                + "PRIMARY KEY (subject_code, biz_date))" + engine(h2));

        // ---------------- AC-3 会计日 / 日终批（不分片） ----------------
        raw.execute("CREATE TABLE IF NOT EXISTS accounting_day ("
                + "biz_date BIGINT PRIMARY KEY,"
                + "status VARCHAR(16) NOT NULL,"
                + "opened_at BIGINT,"
                + "cut_at BIGINT,"
                + "closed_at BIGINT,"
                + "prev_balance_snapshot " + textType(h2) + ","
                + "balance_sum BIGINT,"
                + "total_debit BIGINT,"
                + "total_credit BIGINT,"
                + "accounts INT,"
                + "entries INT)" + engine(h2));
        raw.execute("CREATE TABLE IF NOT EXISTS day_end_report ("
                + "biz_date BIGINT PRIMARY KEY,"
                + "income_total BIGINT NOT NULL DEFAULT 0,"
                + "expense_total BIGINT NOT NULL DEFAULT 0,"
                + "profit BIGINT NOT NULL DEFAULT 0,"
                + "transferred BOOLEAN NOT NULL DEFAULT FALSE,"
                + "transfer_biz_no VARCHAR(64),"
                + "debit_total BIGINT NOT NULL DEFAULT 0,"
                + "credit_total BIGINT NOT NULL DEFAULT 0,"
                + "balance_sum BIGINT NOT NULL DEFAULT 0,"
                + "subject_count INT NOT NULL DEFAULT 0,"
                + "entry_count INT NOT NULL DEFAULT 0,"
                + "legacy_entry_count INT NOT NULL DEFAULT 0,"
                + "status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',"
                + "updated_at BIGINT NOT NULL)" + engine(h2));
        raw.execute("CREATE TABLE IF NOT EXISTS accounting_batch_run ("
                + "id VARCHAR(32) PRIMARY KEY,"
                + "batch_type VARCHAR(32) NOT NULL,"
                + "biz_date BIGINT NOT NULL,"
                + "shard_index INT NOT NULL DEFAULT 0,"
                + "shard_total INT NOT NULL DEFAULT 1,"
                + "status VARCHAR(16) NOT NULL,"
                + "step VARCHAR(32),"
                + "attempt INT NOT NULL DEFAULT 1,"
                + "error VARCHAR(512),"
                + "started_at BIGINT NOT NULL,"
                + "finished_at BIGINT,"
                + "CONSTRAINT uk_batch_run UNIQUE (batch_type, biz_date, shard_index, shard_total))" + engine(h2));
        raw.execute("CREATE TABLE IF NOT EXISTS accounting_day_snapshot ("
                + "biz_no VARCHAR(64) PRIMARY KEY,"
                + "biz_date BIGINT NOT NULL,"
                + "created_at BIGINT NOT NULL)" + engine(h2));

        // ---------------- AC-2/AC-3 分录与凭证 additive 列（含分片物理表） ----------------
        addColumn(raw, h2, "transfer_record", "biz_date", "BIGINT");
        addColumn(raw, h2, "ledger_entry", "subject_code", "VARCHAR(32)");
        addColumn(raw, h2, "ledger_entry", "biz_date", "BIGINT");
        int shardCount = env.getProperty("payment.sharding.shard-count", Integer.class, 4);
        boolean sharding = Boolean.parseBoolean(env.getProperty("payment.sharding.enabled", "false"));
        int physicalAltered = 0;
        if (sharding && !h2) {
            for (int k = 0; k < shardCount; k++) {
                String physical = "ledger_entry_" + k;
                if (!tableExists(raw, physical)) {
                    continue;
                }
                addColumn(raw, false, physical, "subject_code", "VARCHAR(32)");
                addColumn(raw, false, physical, "biz_date", "BIGINT");
                physicalAltered++;
            }
        }
        addIndex(raw, h2, "ledger_entry", "idx_entry_day", "biz_date");
        addIndex(raw, h2, "ledger_entry", "idx_entry_subject", "subject_code");
        if (sharding && !h2) {
            for (int k = 0; k < shardCount; k++) {
                String physical = "ledger_entry_" + k;
                if (!tableExists(raw, physical)) {
                    continue;
                }
                addIndex(raw, false, physical, "idx_entry_day_" + k, "biz_date");
                addIndex(raw, false, physical, "idx_entry_subject_" + k, "subject_code");
            }
        }

        // ---------------- P2-C A-4/AC-6 多币种（全部 additive） ----------------
        upgradeMultiCurrency(raw, h2, sharding, shardCount);

        log.info("账务库增量校验完成 product={} reserveCheck=ready subjects=ready dayCut=ready "
                        + "ledgerEntryCols=subject_code/biz_date physicalShardTablesAltered={}",
                product, physicalAltered);
    }

    /**
     * A-4/AC-6 多币种增量升级：
     * <ul>
     *   <li>新表 fx_rate / fx_exchange / accounting_currency（<b>全部不分片</b>：汇率与换汇单是全局元数据/幂等单据）；</li>
     *   <li>account_balance 增 currency（<b>主键仍 account_id</b>，每账户单币种 ⇒ 分片键不变、同账户同片）；</li>
     *   <li>ledger_entry / transfer_record 增 currency / exchange_rate / rate_scale / base_amount；</li>
     *   <li><b>分片红线</b>：新列同步补到 account_balance_0..N-1 与 ledger_entry_0..N-1 物理表，
     *       否则 ShardRowMirror 整行双写（SELECT * → INSERT）会因列缺失失败。</li>
     * </ul>
     * 存量行新列一律 NULL（只读不回填，CNY 账套按 NULL 读）；绝不 DROP/TRUNCATE/DELETE/UPDATE 既有业务数据。
     */
    private void upgradeMultiCurrency(JdbcTemplate raw, boolean h2, boolean sharding, int shardCount) {
        if (h2) {
            raw.execute("CREATE SEQUENCE IF NOT EXISTS seq_fx_rate START WITH 1");
        } else {
            raw.update("INSERT IGNORE INTO sequences(name, value) VALUES ('seq_fx_rate', 0)");
        }
        raw.execute("CREATE TABLE IF NOT EXISTS fx_rate ("
                + "id VARCHAR(32) PRIMARY KEY,"
                + "from_currency VARCHAR(8) NOT NULL,"
                + "to_currency VARCHAR(8) NOT NULL,"
                + "buy_rate BIGINT NOT NULL,"
                + "sell_rate BIGINT NOT NULL,"
                + "mid_rate BIGINT NOT NULL,"
                + "rate_scale INT NOT NULL DEFAULT 10000,"
                + "version BIGINT NOT NULL,"
                + "effective_at BIGINT NOT NULL,"
                + "biz_date BIGINT,"
                + "source VARCHAR(32),"
                + "operator VARCHAR(64),"
                + "created_at BIGINT NOT NULL,"
                + "CONSTRAINT uk_fx_rate_version UNIQUE (from_currency, to_currency, version))" + engine(h2));
        raw.execute("CREATE TABLE IF NOT EXISTS fx_exchange ("
                + "biz_no VARCHAR(64) PRIMARY KEY,"
                + "from_biz_no VARCHAR(64) NOT NULL,"
                + "to_biz_no VARCHAR(64) NOT NULL,"
                + "from_account_id VARCHAR(32) NOT NULL,"
                + "to_account_id VARCHAR(32) NOT NULL,"
                + "from_currency VARCHAR(8) NOT NULL,"
                + "to_currency VARCHAR(8) NOT NULL,"
                + "amount BIGINT NOT NULL,"
                + "fee BIGINT NOT NULL DEFAULT 0,"
                + "fee_bps INT NOT NULL DEFAULT 0,"
                + "gross_to_amount BIGINT NOT NULL,"
                + "net_to_amount BIGINT NOT NULL,"
                + "fx_gain BIGINT NOT NULL DEFAULT 0,"
                + "fx_gain_account VARCHAR(32),"
                + "fee_account VARCHAR(32),"
                + "buy_rate BIGINT NOT NULL,"
                + "sell_rate BIGINT NOT NULL,"
                + "mid_rate BIGINT NOT NULL,"
                + "rate_scale INT NOT NULL,"
                + "rate_version BIGINT NOT NULL,"
                + "rate_source VARCHAR(32),"
                + "rate_effective_at BIGINT,"
                + "from_balance_after BIGINT,"
                + "to_balance_after BIGINT,"
                + "operator VARCHAR(64),"
                + "created_at BIGINT NOT NULL)" + engine(h2));
        raw.execute("CREATE TABLE IF NOT EXISTS accounting_currency ("
                + "currency VARCHAR(8) PRIMARY KEY,"
                + "base_flag BOOLEAN NOT NULL DEFAULT FALSE,"
                + "created_at BIGINT NOT NULL)" + engine(h2));
        addIndex(raw, h2, "fx_rate", "idx_fx_rate_pair", "from_currency, to_currency, effective_at");

        // 余额表币种标签（主键/分片键 account_id 不变）
        addColumn(raw, h2, "account_balance", "currency", "VARCHAR(8)");
        // 分录/凭证币种 + 汇率快照 + 本位币折算额
        for (String table : List.of("ledger_entry", "transfer_record")) {
            addColumn(raw, h2, table, "currency", "VARCHAR(8)");
            addColumn(raw, h2, table, "exchange_rate", "BIGINT");
            addColumn(raw, h2, table, "rate_scale", "INT");
            addColumn(raw, h2, table, "base_amount", "BIGINT");
        }
        addIndex(raw, h2, "ledger_entry", "idx_entry_currency", "currency");

        // 分片物理表同步补列（account_balance_k 与 ledger_entry_k 都要，缺一即整行双写失败）
        if (sharding && !h2) {
            for (int k = 0; k < shardCount; k++) {
                String balance = "account_balance_" + k;
                if (tableExists(raw, balance)) {
                    addColumn(raw, false, balance, "currency", "VARCHAR(8)");
                }
                String entry = "ledger_entry_" + k;
                if (tableExists(raw, entry)) {
                    addColumn(raw, false, entry, "currency", "VARCHAR(8)");
                    addColumn(raw, false, entry, "exchange_rate", "BIGINT");
                    addColumn(raw, false, entry, "rate_scale", "INT");
                    addColumn(raw, false, entry, "base_amount", "BIGINT");
                    addIndex(raw, false, entry, "idx_entry_currency_" + k, "currency");
                }
            }
        }
        log.info("A-4/AC-6 多币种增量校验完成 fxRate=ready fxExchange=ready currencyRegistry=ready "
                + "cols=account_balance.currency/ledger_entry+transfer_record.currency,exchange_rate,rate_scale,base_amount");
    }

    // ------------------------------------------------------------------ 工具

    private static String engine(boolean h2) {
        return h2 ? "" : " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    }

    private static String textType(boolean h2) {
        return h2 ? "CLOB" : "LONGTEXT";
    }

    /** additive 加列：已存在即跳过（H2 用 IF NOT EXISTS，MySQL 查 information_schema）。 */
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
        log.info("账务库 additive 加列 table={} column={} type={}", table, column, type);
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
            // H2 的 information_schema 大小写口径差异：回落直接探测（SELECT 1 WHERE 1=0）
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

    /** 供端点/验证脚本读取：本域新增表清单（全部不分片）。 */
    public static List<String> newSingleTables() {
        return List.of("accounting_subject", "account_subject_map", "subject_balance", "accounting_day",
                "day_end_report", "accounting_batch_run", "accounting_day_snapshot",
                // P2-C A-4/AC-6：汇率版本 / 换汇单 / 币种账套目录（全局元数据，不分片）
                "fx_rate", "fx_exchange", "accounting_currency");
    }
}