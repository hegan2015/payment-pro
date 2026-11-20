package com.qoder.payment.payment.service;

import com.qoder.payment.payment.dataobject.RefundDO;
import com.qoder.payment.payment.mapper.RefundMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * P-4 退款 PROCESSING 收敛 job（复用 S-5/P-1 模式）：扫描超时在途退款单 →
 * dtx 全局状态 + 渠道终态联合判定 → resumeCommit/resumeCancel/drive。
 * 单笔异常不影响其余；阈值/退避/最大次数与支付补单同一 {@link ReconcilePolicy}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundReconcileJob {

    private final RefundMapper refundMapper;
    private final RefundReconcileService reconcileService;
    private final ReconcilePolicy policy;

    public int reconcileProcessing() {
        if (!policy.isEnabled()) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - policy.getTimeoutMs();
        List<RefundDO> due = refundMapper.selectProcessingBefore(cutoff, policy.getBatchSize());
        int handled = 0;
        for (RefundDO refund : due) {
            try {
                reconcileService.reconverge(refund.getId(), false);
                handled++;
            } catch (Exception e) {
                log.warn("退款补单收敛异常 refundId={}: {}", refund.getId(), e.getMessage());
            }
        }
        if (!due.isEmpty()) {
            log.info("退款 PROCESSING 收敛补单扫描 命中={} 处理={}", due.size(), handled);
        }
        return handled;
    }
}