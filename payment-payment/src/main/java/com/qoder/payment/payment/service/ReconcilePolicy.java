package com.qoder.payment.payment.service;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 补单收敛策略（S-5/P-1）：超时阈值 / 退避 / 最大查询次数 / 批量，均可配（I-2 配置中心就绪后接管）。
 *
 * <p>默认值与 IMPLEMENTATION-PLAN S-5 一致：PROCESSING 超 60s 才主动查渠道；
 * 指数退避避免对渠道/参与者高频轰击；查询次数耗尽只告警转人工，<b>绝不臆断失败</b>。
 */
@Getter
@Component
public class ReconcilePolicy {

    /** 收敛 job 总开关（双 profile 默认开启）。 */
    @Value("${payment.reconcile.enabled:true}")
    private boolean enabled;

    /** 在途单超时阈值（毫秒）：created_at 早于 now-timeout 的 PROCESSING 单才进入补单。 */
    @Value("${payment.reconcile.timeout-ms:60000}")
    private long timeoutMs;

    /** 最大主动查询次数：耗尽后停止自动查询，留 PROCESSING 待人工/差错平台（X-1）介入。 */
    @Value("${payment.reconcile.max-attempts:30}")
    private int maxAttempts;

    @Value("${payment.reconcile.backoff-base-ms:5000}")
    private long backoffBaseMs;

    @Value("${payment.reconcile.backoff-max-ms:60000}")
    private long backoffMaxMs;

    @Value("${payment.reconcile.batch-size:50}")
    private int batchSize;

    /** 第 attempts 次补单后的退避间隔（指数退避，封顶 backoffMaxMs）。 */
    public long backoffMs(int attempts) {
        if (attempts <= 0) {
            return 0L;
        }
        long delay = backoffBaseMs << Math.min(attempts - 1, 16);
        return Math.min(delay, backoffMaxMs);
    }
}