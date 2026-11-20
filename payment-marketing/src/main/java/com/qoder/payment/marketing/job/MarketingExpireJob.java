package com.qoder.payment.marketing.job;

import com.qoder.payment.common.job.JobHandler;
import com.qoder.payment.common.job.LocalScheduledRunner;
import com.qoder.payment.marketing.service.CouponService;
import com.qoder.payment.marketing.service.PointsService;
import com.qoder.payment.marketing.service.RedPacketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * X-3a/X-3b 过期作业（JobHandler 名 {@code marketingExpire}）：券/红包 AVAILABLE→EXPIRED、积分批次按批过期。
 *
 * <p><b>双 profile 零新增中间件</b>：XXL 执行器端口 9993-9999 已用满，本域双 profile 均不接 XXL，
 * 只走本地 {@code @Scheduled} + ShedLock（H2/MySQL 同构锁表）——多实例互斥不重复过期，
 * 进程猝死锁自动到期。作业本身幂等（状态 CAS + remaining&gt;0 守卫），重复触发命中 0 行。
 *
 * <p>控制台/运维可经 {@code MarketingFacade.expireVouchers()/expirePoints()} 立即触发（同样幂等）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketingExpireJob implements JobHandler {

    private static final Duration LOCK_AT_MOST = Duration.ofMinutes(5);

    private final CouponService couponService;
    private final RedPacketService redPacketService;
    private final PointsService pointsService;
    private final ObjectProvider<LockProvider> lockProvider;
    private final ObjectProvider<LocalScheduledRunner> localRunner;

    @Override
    public String name() {
        return "marketingExpire";
    }

    @Override
    public void execute(int shardIndex, int shardTotal, String param) {
        LockProvider provider = lockProvider.getIfAvailable();
        if (provider == null) {
            log.warn("ShedLock 未装配，marketingExpire 直接执行（作业自身幂等吸收重复）");
            runOnce();
            return;
        }
        LockingTaskExecutor executor = new DefaultLockingTaskExecutor(provider);
        LockConfiguration config = new LockConfiguration(ClockProvider.now(), name(), LOCK_AT_MOST, Duration.ZERO);
        executor.executeWithLock((Runnable) this::runOnce, config);
    }

    private void runOnce() {
        int vouchers = couponService.expireDue() + redPacketService.expireDue();
        long points = pointsService.expireDue();
        if (vouchers > 0 || points > 0) {
            log.info("marketingExpire 完成：券/红包过期 {} 张，积分过期 {} 分", vouchers, points);
        }
    }

    /** 本地兜底驱动（I-3 闸门：docker 若将来接 XXL 且该作业被持续驱动则自动让位）。 */
    @Scheduled(fixedDelayString = "${payment.marketing.expire-fixed-delay-ms:60000}")
    public void tick() {
        LocalScheduledRunner runner = localRunner.getIfAvailable();
        if (runner == null) {
            return;
        }
        try {
            runner.runIfLocal(name());
        } catch (Exception e) {
            log.error("marketingExpire 本地兜底执行异常（不影响其它作业）", e);
        }
    }
}