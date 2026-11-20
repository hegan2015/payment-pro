package com.qoder.payment.payment.service;

import com.qoder.payment.payment.dataobject.PaymentDO;
import com.qoder.payment.payment.mapper.PaymentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PROCESSING 收敛 job（CH-1/S-5/P-1，双 profile @Scheduled 兜底，I-3 XXL-Job 就绪后接管调度）。
 *
 * <p>扫描超时在途支付单 → 联合 dtx 全局状态 + 渠道终态判定 → resumeCommit/resumeCancel/drive。
 * 单笔异常不影响其余（逐笔 try/catch）；阈值/退避/最大次数由 {@link ReconcilePolicy} 控制，
 * 收敛动作全部经 TCC，重复扫描无副作用。
 *
 * <p>多实例（I-9）下需 ShedLock 兜底；当前 CAS + 分支幂等已保证重复驱动不产生重复分录/outbox。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconcileJob {

    private final PaymentMapper paymentMapper;
    private final PaymentReconcileService reconcileService;
    private final ReconcilePolicy policy;

    /** 调度入口：返回本轮实际处理的在途单数。 */
    public int reconcileProcessing() {
        if (!policy.isEnabled()) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - policy.getTimeoutMs();
        List<PaymentDO> due = paymentMapper.selectProcessingBefore(cutoff, policy.getBatchSize());
        int handled = 0;
        for (PaymentDO payment : due) {
            try {
                reconcileService.reconverge(payment.getId(), false);
                handled++;
            } catch (Exception e) {
                log.warn("补单收敛异常 paymentId={}: {}", payment.getId(), e.getMessage());
            }
        }
        if (!due.isEmpty()) {
            log.info("PROCESSING 收敛补单扫描 命中={} 处理={}", due.size(), handled);
        }
        return handled;
    }
}