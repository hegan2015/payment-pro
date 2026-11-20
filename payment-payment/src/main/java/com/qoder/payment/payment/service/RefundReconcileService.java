package com.qoder.payment.payment.service;

import com.qoder.payment.common.db.ShardRowMirror;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.payment.RefundDTO;
import com.qoder.payment.payment.dataobject.RefundDO;
import com.qoder.payment.payment.mapper.RefundMapper;
import com.qoder.tcc.GlobalStatus;
import com.qoder.tcc.TccCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * P-4 退款终态查询补单收敛（复用 S-5/P-1 支付补单模式与同一决策表）。
 *
 * <p>触发：① {@link RefundReconcileJob} 扫描超时在途退款单；② 手动/回执后即时收敛。
 * 幂等键：refundId（退款单状态 CAS）+ xid（dtx 全局状态 CAS）+ 参与者分支幂等 +
 * 完结监听「仅 PROCESSING 才翻转并写 outbox」。渠道状态未知只等待，绝不臆断成功/失败；
 * DEAD/查询耗尽只告警转人工。收敛动作全部经 TCC（resumeCommit/resumeCancel/drive），
 * 不绕过协调器直接改退款单状态（除「无协调器记录」资金未动场景）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundReconcileService {

    private final RefundMapper refundMapper;
    private final TccCoordinator coordinator;
    private final ChannelCompletionService channelCompletion;
    private final ReconcilePolicy policy;
    private final ObjectProvider<ShardRowMirror> shardRowMirror;

    public RefundDTO reconverge(String refundId, boolean manual) {
        RefundDO refund = refundMapper.selectById(refundId);
        if (refund == null) {
            throw new BizException(ResultCode.NOT_FOUND, "退款单不存在: " + refundId);
        }
        if (!"PROCESSING".equals(refund.getStatus())) {
            log.info("退款补单幂等跳过（非在途）refundId={} status={}", refundId, refund.getStatus());
            return toDTO(refund);
        }
        long now = System.currentTimeMillis();
        int attempts = refund.getReconcileCount() == null ? 0 : refund.getReconcileCount();
        if (!manual) {
            if (refund.getCreatedAt() != null && now - refund.getCreatedAt() < policy.getTimeoutMs()) {
                return toDTO(refund);
            }
            Long last = refund.getLastReconcileAt();
            if (last != null && now - last < policy.backoffMs(attempts)) {
                return toDTO(refund);
            }
            if (attempts >= policy.getMaxAttempts()) {
                log.error("退款补单查询次数耗尽，转人工/差错介入 refundId={} xid={} attempts={}",
                        refundId, refund.getXid(), attempts);
                return toDTO(refund);
            }
        }
        refundMapper.bumpReconcile(refundId, now);
        mirror(refundId);

        String xid = refund.getXid();
        if (xid == null || xid.isBlank()) {
            refundMapper.finishWithReason(refundId, "FAILED", "补单收敛: 无分布式事务记录，一阶段未执行");
            mirror(refundId);
            log.warn("退款补单收敛置 FAILED（无 dtx 记录）refundId={}", refundId);
            return toDTO(refundMapper.selectById(refundId));
        }
        GlobalStatus dtx = coordinator.status(xid);
        boolean allTryOk = coordinator.allTryOk(xid);
        boolean channelBranch = refund.getChannelOrderNo() != null && !refund.getChannelOrderNo().isBlank();
        String channelStatus = null;
        if (dtx == GlobalStatus.WAITING && allTryOk && channelBranch) {
            channelStatus = channelCompletion.queryStatus(refund.getBizNo());
        }
        PaymentReconcileDecision.Action action =
                PaymentReconcileDecision.decide(dtx, allTryOk, channelStatus, channelBranch);
        String reason = allTryOk ? "退款补单收敛: 渠道终态 " + channelStatus : "退款补单收敛: 一阶段未完成，回滚";
        log.info("退款补单决策 refundId={} xid={} dtx={} allTryOk={} channel={} action={} manual={}",
                refundId, xid, dtx, allTryOk, channelStatus, action, manual);
        switch (action) {
            case RESUME_COMMIT -> channelCompletion.resumeCommit(xid);
            case RESUME_CANCEL -> channelCompletion.resumeCancel(xid, reason);
            case DRIVE -> {
                if (manual) {
                    coordinator.drive(xid);
                }
            }
            case WAIT -> log.info("退款补单等待渠道终态 refundId={} channel={}", refundId, channelStatus);
            case MANUAL -> log.error("退款补单需人工/差错介入 refundId={} xid={} dtx={}", refundId, xid, dtx);
            case NOOP -> log.warn("退款补单不动作：dtx 已终态但退款单仍在途 refundId={} dtx={}", refundId, dtx);
        }
        return toDTO(refundMapper.selectById(refundId));
    }

    private void mirror(String refundId) {
        ShardRowMirror mirror = shardRowMirror.getIfAvailable();
        if (mirror != null) {
            mirror.mirror("refund_record", refundId);
        }
    }

    public static RefundDTO toDTO(RefundDO refund) {
        RefundDTO dto = new RefundDTO();
        dto.setId(refund.getId());
        dto.setPaymentId(refund.getPaymentId());
        dto.setAmount(refund.getAmount());
        dto.setFeeRefund(refund.getFeeRefund());
        dto.setStatus(refund.getStatus());
        dto.setRequestId(refund.getRequestId());
        dto.setBizNo(refund.getBizNo());
        dto.setChannelOrderNo(refund.getChannelOrderNo());
        dto.setFailReason(refund.getFailReason());
        dto.setReconcileCount(refund.getReconcileCount());
        dto.setCreatedAt(refund.getCreatedAt());
        return dto;
    }
}