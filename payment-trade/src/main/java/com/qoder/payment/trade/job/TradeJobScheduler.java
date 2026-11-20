package com.qoder.payment.trade.job;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.job.LocalScheduledRunner;
import com.qoder.payment.trade.product.TradeProductKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * T-1/T-4 本地调度兜底（local 零依赖，docker XXL-Job 正常驱动时静默待命）。
 *
 * <p><b>默认不跑批</b>：两个自动开关（{@code trade.expire.auto.enabled} /
 * {@code trade.escrow.auto.enabled}）默认 <b>false</b> —— 超时关闭与担保自动确认只由
 * XXL-Job、控制台或 {@code /actuator/ppjob} 显式触发，绝不自动跑批干扰既有断言（smoke/verify-pro）。
 * 运维经 DynamicConfig 打开后即按本扫描周期常驻兜底（配置热更生效，无需重启）。
 *
 * <p>开关关闭时本方法只做两次配置读取即返回，不建连接、不触发作业、不写任何数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeJobScheduler {

    private final ObjectProvider<LocalScheduledRunner> localRunner;
    private final DynamicConfig dynamicConfig;

    @Scheduled(fixedDelayString = "${trade.job.scan-interval-ms:60000}")
    public void scan() {
        boolean expire = dynamicConfig.getBool(TradeProductKeys.EXPIRE_AUTO_ENABLED, false);
        boolean escrow = dynamicConfig.getBool(TradeProductKeys.ESCROW_AUTO_ENABLED, false);
        if (!expire && !escrow) {
            return;
        }
        LocalScheduledRunner runner = localRunner.getIfAvailable();
        if (runner == null) {
            return;
        }
        if (expire) {
            runner.runIfLocal("tradeOrderExpire");
        }
        if (escrow) {
            runner.runIfLocal("escrowAutoConfirm");
        }
    }
}