package com.qoder.payment.common.job;

import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * I-3 XXL-Job 执行器注册（{@code payment.job.xxl.enabled=true} 才装配，docker 专用）。
 *
 * <p>把本域所有 {@link JobHandler} 以 SPI 适配器注册进执行器（AppName 默认 {@code payment-executor}），
 * 执行器启动后向共享栈调度中心 {@code claim-shared-xxl-job-admin:8094} 自动注册（30s 心跳），
 * accessToken 经 {@code ~/.config/payment-pro/docker.env} 注入，不落仓库。
 *
 * <p><b>降级红线（三重）</b>：
 * <ol>
 *   <li>执行器启动/注册抛错一律吞掉并告警，绝不阻断应用启动（调度中心宕机不影响支付主链路）；</li>
 *   <li>独立守护线程每 {@code probe-interval-ms} 探测调度中心可达性，写入 {@link JobRuntimeState}；</li>
 *   <li>不可达/未注册/作业长时间未被触发 → {@link LocalScheduledRunner} 自动回切本地 @Scheduled 兜底。</li>
 * </ol>
 *
 * <p>在 {@link ApplicationReadyEvent} 后启动：不与 Dubbo 服务导出/Bean 初始化争抢，
 * 启动窗口内本地兜底照常驱动（ShedLock 去重）。
 */
public class XxlJobRegistrar implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(XxlJobRegistrar.class);

    private final JobSettings settings;
    private final JobRuntimeState state;
    private final JobHandlerRegistry registry;
    private final ApplicationContext applicationContext;

    private volatile XxlJobSpringExecutor executor;
    private volatile ScheduledExecutorService probe;
    private volatile boolean lastReachable;
    private volatile boolean started;

    public XxlJobRegistrar(JobSettings settings, JobRuntimeState state, JobHandlerRegistry registry,
                           ApplicationContext applicationContext) {
        this.settings = settings;
        this.state = state;
        this.registry = registry;
        this.applicationContext = applicationContext;
        state.setXxlEnabled(true);
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (started) {
            return;
        }
        started = true;
        if (registry.names().isEmpty()) {
            log.warn("I-3 本域无 JobHandler，跳过 XXL-Job 执行器启动 app={}", state.applicationName());
            return;
        }
        for (String name : registry.names()) {
            XxlJobExecutor.registJobHandler(name, new XxlJobHandlerAdapter(name, registry, state));
        }
        try {
            XxlJobSpringExecutor ex = new XxlJobSpringExecutor();
            ex.setAdminAddresses(settings.adminAddresses());
            ex.setAppname(settings.appname());
            ex.setIp(settings.executorIp());
            ex.setPort(settings.executorPort());
            ex.setAccessToken(settings.accessToken());
            ex.setLogPath(settings.logPath());
            ex.setLogRetentionDays(settings.logRetentionDays());
            ex.setApplicationContext(applicationContext);
            ex.afterSingletonsInstantiated();
            this.executor = ex;
            state.setExecutorRegistered(true);
            log.info("I-3 XXL-Job 执行器启动成功 appname={} port={} admin={} handlers={} ip={}",
                    settings.appname(), settings.executorPort(), settings.adminAddresses(),
                    registry.names(), settings.executorIp());
        } catch (Throwable e) {
            // 红线：调度中心不可达/端口冲突等一律不阻断启动，本地 @Scheduled 兜底常驻
            state.setExecutorRegistered(false);
            log.error("[告警] I-3 XXL-Job 执行器启动失败，本地 @Scheduled 兜底继续驱动 appname={} admin={}: {}",
                    settings.appname(), settings.adminAddresses(), e.toString());
        }
        startProbe();
    }

    /** 调度中心可达性探测（守护线程，不占用 Spring 调度池，不影响 local fixedDelay 语义）。 */
    private void startProbe() {
        probe = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pp-xxl-admin-probe");
            t.setDaemon(true);
            return t;
        });
        long interval = Math.max(500L, settings.probeIntervalMs());
        probe.scheduleWithFixedDelay(this::probeAdmin, 0, interval, TimeUnit.MILLISECONDS);
    }

    private void probeAdmin() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(settings.probeUrl()).toURL().openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            // 任何 HTTP 应答（含 302 登录跳转）都说明调度中心进程存活
            state.setAdminReachable(true, null);
            if (!lastReachable) {
                log.info("I-3 XXL-Job 调度中心可达 url={} http={}", settings.probeUrl(), code);
            }
            lastReachable = true;
        } catch (Exception e) {
            state.setAdminReachable(false, e.getClass().getSimpleName() + ": " + e.getMessage());
            if (lastReachable) {
                log.error("[告警] I-3 XXL-Job 调度中心不可达，本地 @Scheduled 兜底接管 url={}: {}",
                        settings.probeUrl(), e.toString());
            }
            lastReachable = false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Override
    public void destroy() {
        if (probe != null) {
            probe.shutdownNow();
        }
        if (executor != null) {
            try {
                executor.destroy();
            } catch (Exception e) {
                log.warn("I-3 XXL-Job 执行器关闭异常: {}", e.toString());
            }
        }
    }
}