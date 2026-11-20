package com.qoder.payment.common.job;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * I-3 分布式调度自动装配（引入 payment-common 即生效，跨域基础设施 §0.6）。
 *
 * <p><b>只对本域注册了 {@link JobHandler} 的应用生效</b>：无作业的域（gateway/member/risk/accounting/
 * trade/settle/channel/cashier/account）不建锁表、不起线程、不加代理，零影响。
 *
 * <p>双 profile：
 * <ul>
 *   <li>local（默认）：{@link LocalScheduledRunner} 包装既有 @Scheduled + ShedLock H2 锁，零依赖不连 XXL-Job；</li>
 *   <li>docker：{@code payment.job.xxl.enabled=true} → {@link XxlJobRegistrar} 注册执行器到共享栈 :8094，
 *       本地兜底静默待命；调度中心不可达/未驱动某作业 → 自动回切本地兜底（资金一致性服务端常驻）。</li>
 * </ul>
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(name = "net.javacrumbs.shedlock.core.LockProvider")
@ConditionalOnBean(JobHandler.class)
public class JobAutoConfiguration {

    @Bean
    public JobSettings paymentJobSettings(Environment env) {
        return JobSettings.from(env);
    }

    @Bean
    public JobRuntimeState paymentJobRuntimeState(Environment env) {
        return new JobRuntimeState(env.getProperty("spring.application.name", "app"));
    }

    @Bean
    public JobHandlerRegistry paymentJobHandlerRegistry(ObjectProvider<JobHandler> handlers, JobRuntimeState state) {
        List<JobHandler> all = handlers.orderedStream().toList();
        return new JobHandlerRegistry(all, state);
    }

    /** local 兜底：未设置 payment.job.local.enabled 即装配（matchIfMissing=true）。 */
    @Bean
    @ConditionalOnProperty(prefix = "payment.job.local", name = "enabled", havingValue = "true", matchIfMissing = true)
    public LocalScheduledRunner paymentLocalScheduledRunner(JobSettings settings, JobRuntimeState state,
                                                            JobHandlerRegistry registry) {
        return new LocalScheduledRunner(settings, state, registry);
    }

    /** ShedLock JDBC 锁（H2/MySQL 同构，BIGINT 毫秒锁表）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(javax.sql.DataSource.class)
    @Import(ShedLockConfig.class)
    static class ShedLockImportConfiguration {
    }

    /** XXL-Job 执行器（docker 专用；classpath 无 xxl-job-core 的域自动跳过）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.xxl.job.core.executor.impl.XxlJobSpringExecutor")
    @ConditionalOnProperty(prefix = "payment.job.xxl", name = "enabled", havingValue = "true")
    static class XxlJobConfiguration {

        @Bean
        public XxlJobRegistrar paymentXxlJobRegistrar(JobSettings settings, JobRuntimeState state,
                                                      JobHandlerRegistry registry, ApplicationContext ctx) {
            return new XxlJobRegistrar(settings, state, registry, ctx);
        }
    }

    /** 运维端点 /actuator/ppjob（需域配置 exposure.include 列出 ppjob）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    static class JobEndpointConfiguration {

        @Bean
        public JobControlEndpoint paymentJobControlEndpoint(JobSettings settings, JobRuntimeState state,
                                                            JobHandlerRegistry registry,
                                                            ObjectProvider<LocalScheduledRunner> localRunner,
                                                            ObjectProvider<org.springframework.jdbc.core.JdbcTemplate> jdbc) {
            return new JobControlEndpoint(settings, state, registry, localRunner, jdbc);
        }
    }
}