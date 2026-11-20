package com.qoder.payment.accounting.daycut;

import com.qoder.payment.common.job.LocalScheduledRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AC-3 本地调度兜底（I-3 语义）：把日切/日终批交给 {@link LocalScheduledRunner} 统一闸门，
 * XXL-Job 正常驱动时静默待命、调度中心不可达时自动回切（资金兜底常驻）。
 *
 * <p><b>默认不自动跑批</b>：{@code accounting.daycut.auto-enabled=false}（DynamicConfig 可热更）时
 * 本兜底直接返回，不触发任何批处理 —— 与 recon/settle 先例一致，避免自动跑批干扰既有断言。
 * 需要自动日切时把该键置 true 即可（日切时点由 {@code accounting.daycut.at} 配置）。
 * 手动触发路径：{@code POST /actuator/ppjob/accountingDayCut}、控制台、XXL-Job。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountingDayScheduler {

    private final AccountingDayClock clock;
    private final ObjectProvider<LocalScheduledRunner> localRunner;

    /** 每分钟评估一次是否到日切时点（默认关闭，零副作用）。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void tick() {
        if (!clock.autoCutEnabled()) {
            return;
        }
        LocalScheduledRunner runner = localRunner.getIfAvailable();
        if (runner == null) {
            return;
        }
        runner.runIfLocal("accountingDayCut");
        runner.runIfLocal("accountingDayEnd");
    }
}