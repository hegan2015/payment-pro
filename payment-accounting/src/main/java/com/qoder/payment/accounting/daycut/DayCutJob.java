package com.qoder.payment.accounting.daycut;

import com.qoder.payment.common.job.JobHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AC-3 会计日切作业（JobHandler 名 {@code accountingDayCut}）。
 *
 * <p>经 I-3 注册表触发（XXL-Job / 控制台 / {@code POST /actuator/ppjob/accountingDayCut}），
 * 互斥由 {@link AccountingDayService#runDayCut} 内的 ShedLock 全局锁
 * {@code accountingDayCut:<bizDate>} + 会计日状态 CAS 保证：<b>多实例不重复日切</b>。
 * 日切是全局日历动作，不按分片拆分；分片参数只透传给科目汇总（不重不漏）。
 *
 * <p>param：会计日 epoch millis（可空 = 当前 OPEN 日）。
 *
 * <p><b>不自动跑批</b>：本域 {@code @Scheduled} 兜底只在
 * {@code accounting.daycut.auto-enabled=true} 时才调用注册表（默认 false），
 * 与 recon/settle 先例一致，绝不干扰既有断言。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DayCutJob implements JobHandler {

    private final AccountingDayService dayService;

    @Override
    public String name() {
        return "accountingDayCut";
    }

    @Override
    public void execute(int shardIndex, int shardTotal, String param) {
        Long bizDate = parseDay(param);
        var dto = dayService.runDayCut(bizDate, shardIndex, shardTotal);
        log.info("AC-3 日切作业完成 job={} shard={}/{} bizDate={} status={} executed={} message={}",
                name(), shardIndex, shardTotal, dto.getBizDate(), dto.getStatus(), dto.getExecuted(), dto.getMessage());
    }

    static Long parseDay(String param) {
        if (param == null || param.isBlank()) {
            return null;
        }
        String raw = param.trim();
        if (raw.startsWith("force")) {
            raw = raw.substring(5).replaceFirst("^:", "").trim();
        }
        if (raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            log.warn("AC-3 日切参数非法（忽略 bizDate，用当前 OPEN 日）param={}", param);
            return null;
        }
    }
}