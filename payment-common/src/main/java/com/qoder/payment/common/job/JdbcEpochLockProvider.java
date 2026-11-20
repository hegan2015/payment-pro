package com.qoder.payment.common.job;

import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.support.AbstractStorageAccessor;
import net.javacrumbs.shedlock.support.StorageBasedLockProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.BiConsumer;

/**
 * I-3 ShedLock JDBC 锁提供者（BIGINT 毫秒版）。
 *
 * <p>锁算法完全复用 ShedLock 官方 {@link StorageBasedLockProvider}（insert 抢占 → CAS update →
 * unlock 置 lock_until=unlockTime），只把存储层 SQL 换成项目约定的 <b>BIGINT epoch millis</b> 列：
 * 官方 {@code shedlock-provider-jdbc-template} 绑定 {@code java.sql.Timestamp}，与本仓库
 * §0.6「时间戳 BIGINT 毫秒」DDL 约定（{@code lock_until BIGINT / locked_at BIGINT}）不兼容，
 * 故按同一算法实现 BIGINT 存储访问器，H2(local) 与 MySQL(docker) 同构。
 *
 * <p>每条锁 SQL 走 {@code PROPAGATION_REQUIRES_NEW} 独立事务（与官方一致）：即使业务方法处于
 * 事务中，锁记录也立即对其他实例可见，不会因业务事务未提交而失效。
 */
public class JdbcEpochLockProvider extends StorageBasedLockProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcEpochLockProvider.class);

    private final BiConsumer<String, Boolean> lockListener;

    public JdbcEpochLockProvider(JdbcTemplate jdbcTemplate, String tableName, String lockedBy,
                                 BiConsumer<String, Boolean> lockListener) {
        super(new EpochStorageAccessor(jdbcTemplate, tableName, lockedBy));
        this.lockListener = lockListener;
        log.info("I-3 ShedLock JDBC 锁就绪 table={} lockedBy={}（BIGINT 毫秒，H2/MySQL 同构）", tableName, lockedBy);
    }

    @Override
    protected boolean doLock(LockConfiguration lockConfiguration) {
        boolean acquired = super.doLock(lockConfiguration);
        if (lockListener != null) {
            lockListener.accept(lockConfiguration.getName(), acquired);
        }
        if (!acquired && log.isDebugEnabled()) {
            log.debug("ShedLock 锁被他人持有，本次跳过 name={}", lockConfiguration.getName());
        }
        return acquired;
    }

    /** BIGINT 毫秒存储访问器：语义与官方 JdbcTemplateStorageAccessor 逐条对齐。 */
    static final class EpochStorageAccessor extends AbstractStorageAccessor {

        private final JdbcTemplate jdbc;
        private final TransactionTemplate tx;
        private final String table;
        private final String lockedBy;

        EpochStorageAccessor(JdbcTemplate jdbc, String table, String lockedBy) {
            this.jdbc = jdbc;
            this.table = table;
            this.lockedBy = lockedBy;
            this.tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }

        private long now() {
            return ClockProvider.now().toEpochMilli();
        }

        @Override
        public boolean insertRecord(LockConfiguration cfg) {
            String sql = "INSERT INTO " + table + "(name, lock_until, locked_at, locked_by) VALUES(?, ?, ?, ?)";
            try {
                Boolean inserted = tx.execute(status -> jdbc.update(sql, cfg.getName(),
                        cfg.getLockAtMostUntil().toEpochMilli(), now(), lockedBy) > 0);
                return Boolean.TRUE.equals(inserted);
            } catch (DataIntegrityViolationException e) {
                // 记录已存在（其他实例先建，DuplicateKeyException 是其子类一并覆盖）：交由 updateRecord 做 CAS 抢占
                return false;
            }
        }

        @Override
        public boolean updateRecord(LockConfiguration cfg) {
            String sql = "UPDATE " + table + " SET lock_until = ?, locked_at = ?, locked_by = ? "
                    + "WHERE name = ? AND lock_until <= ?";
            long now = now();
            Boolean updated = tx.execute(status -> jdbc.update(sql, cfg.getLockAtMostUntil().toEpochMilli(),
                    now, lockedBy, cfg.getName(), now) > 0);
            return Boolean.TRUE.equals(updated);
        }

        @Override
        public void unlock(LockConfiguration cfg) {
            String sql = "UPDATE " + table + " SET lock_until = ? WHERE name = ?";
            tx.executeWithoutResult(status -> jdbc.update(sql, cfg.getUnlockTime().toEpochMilli(), cfg.getName()));
        }

        @Override
        public boolean extend(LockConfiguration cfg) {
            String sql = "UPDATE " + table + " SET lock_until = ? WHERE name = ? AND locked_by = ? AND lock_until > ?";
            Boolean updated = tx.execute(status -> jdbc.update(sql, cfg.getLockAtMostUntil().toEpochMilli(),
                    cfg.getName(), lockedBy, now()) > 0);
            return Boolean.TRUE.equals(updated);
        }
    }
}