package com.qoder.payment.recon.service;

import com.qoder.payment.common.job.JobHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * RC-3 渠道内外对账作业（JobHandler 名 {@code reconChannel}）。
 *
 * <p>锁名 {@code reconChannel:<channelCode>:<bizDate>}：同渠道同日多实例互斥，不同渠道/日可并行。
 * param：{@code <channelCode>[:<epochMillis|yyyyMMdd>]}（可空 = MOCK_BANK + 当前账面会计日）。
 *
 * <p><b>默认不自动跑批</b>：由 XXL-Job（任务记录 trigger_status=0 建好待启用）/ 控制台 / ppjob 触发。
 * 失败可重跑幂等（run/diff 唯一键复用 + 差错单去重）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelReconJob implements JobHandler {

    private static final Duration LOCK_AT_MOST = Duration.ofMinutes(15);

    private final ChannelReconService channelReconService;
    private final DailyReconService dailyReconService;
    private final ObjectProvider<LockProvider> lockProvider;

    @Override
    public String name() {
        return "reconChannel";
    }

    @Override
    public void execute(int shardIndex, int shardTotal, String param) {
        String code = "MOCK_BANK";
        Long dayParam = null;
        if (param != null && !param.isBlank()) {
            String[] parts = param.trim().split(":", 2);
            if (!parts[0].isBlank()) {
                code = parts[0].trim();
            }
            if (parts.length > 1 && !parts[1].isBlank()) {
                dayParam = DailyReconService.parseDayText(parts[1]);
            }
        }
        long day = dailyReconService.resolveDay(dayParam);
        LockProvider provider = lockProvider.getIfAvailable();
        if (provider == null) {
            log.warn("ShedLock 未装配，reconChannel 直接执行 channel={} bizDate={}", code, day);
            channelReconService.runChannelRecon(code, day);
            return;
        }
        LockingTaskExecutor executor = new DefaultLockingTaskExecutor(provider);
        LockConfiguration config = new LockConfiguration(ClockProvider.now(),
                name() + ":" + code + ":" + day, LOCK_AT_MOST, Duration.ZERO);
        String channel = code;
        executor.executeWithLock((Runnable) () -> channelReconService.runChannelRecon(channel, day), config);
    }
}