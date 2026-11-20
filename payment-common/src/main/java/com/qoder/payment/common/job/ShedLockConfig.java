package com.qoder.payment.common.job;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * I-3 ShedLock 装配：JDBC 分布式锁（local H2 / docker MySQL 同构），
 * 只在「本域确实注册了 {@link JobHandler}」且 classpath 有 JDBC 时生效，
 * 不给无作业的域（gateway/member/risk/accounting 等）增加任何表或代理。
 *
 * <p>{@code @EnableSchedulerLock(PROXY_METHOD)}：凡 {@code @SchedulerLock} 注解的方法，
 * 无论由 Spring @Scheduled、XXL-Job 还是控制台触发，都经代理加锁 —— 多实例只有一个执行（防 E-6 重复驱动）。
 *
 * <p>锁名约定：{@code recoverTransactions / dispatchOutbox / reconcileProcessing / notifyPush}；
 * 分片作业（reconRun）按 {@code reconRun:<idx>/<total>} 逐片加锁，见 recon 域实现。
 */
@Configuration(proxyBeanMethods = false)
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M", defaultLockAtLeastFor = "PT0S",
        interceptMode = EnableSchedulerLock.InterceptMode.PROXY_METHOD, proxyTargetClass = true)
@ConditionalOnClass({JdbcTemplate.class, LockProvider.class, DataSource.class})
@ConditionalOnBean(JobHandler.class)
@ConditionalOnProperty(prefix = "payment.job.shedlock", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ShedLockConfig {

    @Bean
    public ShedLockSchemaInitializer shedLockSchemaInitializer(DataSource dataSource, JobSettings settings) {
        return new ShedLockSchemaInitializer(new JdbcTemplate(dataSource), settings.shedlockTable());
    }

    /** 启动时回收猝死进程留下的孤儿锁（只动协调表），避免混沌重启后补偿停摆到 lockAtMostFor 到期。 */
    @Bean
    public ShedLockOrphanRecovery shedLockOrphanRecovery(DataSource dataSource, JobSettings settings,
                                                         ShedLockSchemaInitializer initializer) {
        return new ShedLockOrphanRecovery(new JdbcTemplate(dataSource), settings.shedlockTable());
    }

    @Bean
    @ConditionalOnMissingBean(LockProvider.class)
    public LockProvider shedLockProvider(DataSource dataSource, JobSettings settings, JobRuntimeState state,
                                         ShedLockSchemaInitializer initializer,
                                         ShedLockOrphanRecovery orphanRecovery) {
        // 依赖 initializer/orphanRecovery 保证锁表先建好、孤儿锁先回收；锁获取/跳过计数进状态与指标（I-8 告警接线）
        return new JdbcEpochLockProvider(new JdbcTemplate(dataSource), settings.shedlockTable(),
                state.lockedBy(), state::markLockOutcome);
    }
}