package com.qoder.payment.accounting.daycut;

import com.qoder.payment.common.job.JobHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AC-3 日终批处理作业（JobHandler 名 {@code accountingDayEnd}）：科目汇总 + 损益报告（+ 可选结转）。
 *
 * <p><b>分片不重不漏</b>：逐片 ShedLock {@code accountingDayEnd:<day>:<i>/<n>}（同片多实例互斥、
 * 各片可并行），科目按 {@code ShardKeys.shardOf(subjectCode, shardTotal)} 取模归属唯一片。
 *
 * <p>param：会计日 epoch millis（可空 = 当前 OPEN 日）。失败留痕 FAILED，重跑幂等续跑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DayEndJob implements JobHandler {

    private final AccountingDayService dayService;

    @Override
    public String name() {
        return "accountingDayEnd";
    }

    @Override
    public void execute(int shardIndex, int shardTotal, String param) {
        Long bizDate = DayCutJob.parseDay(param);
        var dto = dayService.runDayEnd(bizDate, shardIndex, shardTotal);
        log.info("AC-3 日终批作业完成 job={} shard={}/{} report={}", name(), shardIndex, shardTotal,
                dto == null ? "锁未获取（同片其它实例执行）" : dto.getStatus());
    }
}