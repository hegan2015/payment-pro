package com.qoder.payment.risk.aml;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.job.JobHandler;
import com.qoder.payment.common.job.LocalScheduledRunner;
import com.qoder.payment.risk.config.RiskPlusKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * R-6 AML 监测作业（I-3 JobHandler 名 {@code riskAmlMonitor}）。
 *
 * <p>日切/周期扫描 {@code aml_txn} 流水，评估聚合类可疑规则（拆分/结构化/快进快出/累计大额），
 * 命中生成 STR（幂等键 payer+rule+bizDate，重复扫描不重复建报告）。
 * <p>互斥：{@code @SchedulerLock} 全局锁（local H2 / docker MySQL 同构）+ 报告唯一键双保险。
 * <p><b>默认不自动跑批</b>（{@code risk.aml.auto-scan-enabled=false}，与 recon/settle/accounting
 * 先例一致，绝不干扰既有断言）；手动触发经 {@code POST /api/risk/aml/scan} 或
 * {@code POST /actuator/ppjob/riskAmlMonitor}，两条路径同一代码。
 * <p>双 profile：local @Scheduled 兜底零依赖；docker 由 XXL-Job 接管时本地静默待命、失联自动回切。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AmlMonitorJob implements JobHandler {

    private final AmlService amlService;
    private final DynamicConfig config;
    private final ObjectProvider<LocalScheduledRunner> localRunner;

    @Override
    public String name() {
        return "riskAmlMonitor";
    }

    /** 本地兜底触发（默认闸门关闭，开启后按配置周期扫描）。 */
    @Scheduled(fixedDelayString = "${payment.risk.aml.scan-interval-ms:60000}")
    public void scheduledScan() {
        if (!config.getBool(RiskPlusKeys.AML_AUTO_SCAN, RiskPlusKeys.DEFAULT_AML_AUTO_SCAN)) {
            return;
        }
        LocalScheduledRunner runner = localRunner.getIfAvailable();
        if (runner != null) {
            runner.runIfLocal(name());
        } else {
            executeQuietly(0, 1, null);
        }
    }

    @Override
    @SchedulerLock(name = "riskAmlMonitor", lockAtMostFor = "PT4M", lockAtLeastFor = "PT0S")
    public void execute(int shardIndex, int shardTotal, String param) {
        String bizDate = param == null || param.isBlank() ? null : param.trim();
        var result = amlService.scan(bizDate, null);
        log.info("R-6 AML 监测作业完成 job={} shard={}/{} bizDate={} hits={} created={} reused={} degraded={}",
                name(), shardIndex, shardTotal, result.getBizDate(), result.getHits(), result.getCreated(),
                result.getReused(), result.getDegraded());
    }

    private void executeQuietly(int idx, int total, String param) {
        try {
            execute(idx, total, param);
        } catch (Exception e) {
            log.warn("R-6 AML 监测作业异常（本轮跳过，不影响下一轮，不阻断交易）: {}", e.getMessage());
        }
    }
}