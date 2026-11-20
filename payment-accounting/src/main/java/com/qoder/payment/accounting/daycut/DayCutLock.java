package com.qoder.payment.accounting.daycut;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * AC-3 日切/日终批的 ShedLock 编程式加锁封装（I-3）。
 *
 * <p>用编程式 API 而非 {@code @SchedulerLock}：注解锁名静态，无法按会计日/分片区分
 * （共用锁名会互相跳过导致漏结，与 ST-1 settleBatch 同一理由）。
 *
 * <p>锁表与业务同库（不分片），多实例共享同一 MySQL/H2 即天然互斥；
 * 未装配 LockProvider（local 关闭 shedlock）时直接执行，由 CAS + 幂等键吸收重复。
 * 锁未获取 ⇒ 执行 fallback（返回既有状态，绝不重复动账）。
 */
@Slf4j
@Component
public class DayCutLock {

    private final ObjectProvider<LockProvider> lockProvider;

    public DayCutLock(ObjectProvider<LockProvider> lockProvider) {
        this.lockProvider = lockProvider;
    }

    public <T> T withLock(String name, Duration atMost, Supplier<T> action, Supplier<T> fallback) {
        LockProvider provider = lockProvider.getIfAvailable();
        if (provider == null) {
            log.warn("ShedLock 未装配，{} 直接执行（CAS + 幂等键吸收重复）", name);
            return action.get();
        }
        LockingTaskExecutor executor = new DefaultLockingTaskExecutor(provider);
        LockConfiguration config = new LockConfiguration(ClockProvider.now(), name, atMost, Duration.ZERO);
        try {
            LockingTaskExecutor.TaskResult<T> result = executor.executeWithLock(action::get, config);
            if (result.wasExecuted()) {
                return result.getResult();
            }
            log.info("AC-3 锁未获取（其它实例正在执行）lock={}", name);
            return fallback.get();
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("AC-3 作业执行失败 lock=" + name + ": " + e, e);
        }
    }
}