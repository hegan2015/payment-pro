package com.qoder.payment.payment.service;

import com.qoder.payment.common.db.ShardRowMirror;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.payment.convert.PaymentConverter;
import com.qoder.payment.payment.dataobject.PaymentDO;
import com.qoder.payment.payment.mapper.PaymentMapper;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.tcc.GlobalStatus;
import com.qoder.tcc.TccCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 支付终态查询补单收敛（CH-1 / S-5 / P-1）。
 *
 * <p>触发：① {@link PaymentReconcileJob} 扫描超时在途单（PROCESSING 超阈值）；
 * ② 手动/回执后即时收敛 {@code reconverge(paymentId, true)}（运营与商户补单入口）。
 *
 * <p>幂等键：paymentId（支付单状态 CAS）+ xid（dtx 全局状态 CAS）+ 参与者分支幂等
 * （tcc_branch_log 主键）+ 完结监听「仅 PROCESSING 才翻转并写 outbox」。
 * 因此重复补单/回执与补单并发都不会产生重复分录或重复 outbox 行。
 *
 * <p>资金红线：不绕过 TCC 直接改支付单状态；渠道状态未知只等待；
 * DEAD/查询耗尽只告警转人工（X-1 差错平台就绪后挂差错单），绝不臆断失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReconcileService {

    private final PaymentMapper paymentMapper;
    private final TccCoordinator coordinator;
    private final ChannelCompletionService channelCompletion;
    private final ReconcilePolicy policy;
    /** P-7：begin 前崩溃恢复置 FAILED 时，须退回已先行入账的营销补贴（与风控拒绝/onCancelled 出口同范式）。 */
    private final MarketingPayService marketingPayService;
    /** I-5b：补单留痕/终态翻转同样双写到影子侧（未装配分片时为 null，零影响）。 */
    private final org.springframework.beans.factory.ObjectProvider<ShardRowMirror> shardRowMirror;

    private void mirror(String table, Object pk) {
        ShardRowMirror mirror = shardRowMirror.getIfAvailable();
        if (mirror != null) {
            mirror.mirror(table, pk);
        }
    }

    /**
     * 收敛一笔在途支付单。
     *
     * @param manual true=手动/回执触发（忽略超时阈值与退避，仍受决策表与 CAS 约束）；
     *               false=调度补单（受阈值/退避/最大次数约束）
     */
    public PaymentDTO reconverge(String paymentId, boolean manual) {
        PaymentDO payment = paymentMapper.selectById(paymentId);
        if (payment == null) {
            throw new BizException(ResultCode.NOT_FOUND, "支付单不存在: " + paymentId);
        }
        if (!"PROCESSING".equals(payment.getStatus())) {
            log.info("补单幂等跳过（非在途）paymentId={} status={}", paymentId, payment.getStatus());
            return PaymentConverter.toDTO(payment);
        }
        long now = System.currentTimeMillis();
        int attempts = payment.getReconcileCount() == null ? 0 : payment.getReconcileCount();
        if (!manual) {
            if (payment.getCreatedAt() != null && now - payment.getCreatedAt() < policy.getTimeoutMs()) {
                return PaymentConverter.toDTO(payment);
            }
            Long last = payment.getLastReconcileAt();
            if (last != null && now - last < policy.backoffMs(attempts)) {
                return PaymentConverter.toDTO(payment);
            }
            if (attempts >= policy.getMaxAttempts()) {
                log.error("补单查询次数耗尽，转人工/差错介入 paymentId={} xid={} attempts={}",
                        paymentId, payment.getXid(), attempts);
                return PaymentConverter.toDTO(payment);
            }
        }
        paymentMapper.bumpReconcile(paymentId, now);
        mirror("payment", paymentId);

        String xid = payment.getXid();
        if (xid == null || xid.isBlank()) {
            // 协调器记录缺失：begin 之前崩溃，TCC 分支从未注册、TCC 资金从未预留 → CAS 置 FAILED（幂等）。
            // 但 P-7 营销补贴先于 TCC begin 独立动账（券已核销、D SYS_MARKETING/C 用户户已入账），
            // 故此处 FAILED 仍须退回营销权益，否则补贴滞留 + 券白核销（marketing_status=APPLIED 不被兜底作业覆盖）。
            paymentMapper.finish(paymentId, "FAILED", "补单收敛: 无分布式事务记录，一阶段未执行");
            if (payment.getMarketingAmount() != null && payment.getMarketingAmount() > 0) {
                marketingPayService.rollbackQuietly(paymentId, "补单收敛(无dtx): 营销回退");
            }
            mirror("payment", paymentId);
            log.warn("补单收敛置 FAILED（无 dtx 记录）paymentId={}", paymentId);
            return reload(paymentId);
        }

        GlobalStatus dtx = coordinator.status(xid);
        boolean allTryOk = coordinator.allTryOk(xid);
        boolean channelBranch = payment.getChannelBizNo() != null && !payment.getChannelBizNo().isBlank();
        String channelStatus = null;
        if (dtx == GlobalStatus.WAITING && allTryOk && channelBranch) {
            channelStatus = channelCompletion.queryStatus(payment.getChannelBizNo());
        }
        PaymentReconcileDecision.Action action =
                PaymentReconcileDecision.decide(dtx, allTryOk, channelStatus, channelBranch);
        String reason = allTryOk ? "补单收敛: 渠道终态 " + channelStatus : "补单收敛: 一阶段未完成，回滚";
        log.info("补单决策 paymentId={} xid={} dtx={} allTryOk={} channel={} action={} manual={}",
                paymentId, xid, dtx, allTryOk, channelStatus, action, manual);
        switch (action) {
            case RESUME_COMMIT -> {
                boolean driven = channelCompletion.resumeCommit(xid);
                log.info("补单 resumeCommit paymentId={} xid={} driven={}", paymentId, xid, driven);
            }
            case RESUME_CANCEL -> {
                boolean driven = channelCompletion.resumeCancel(xid, reason);
                log.info("补单 resumeCancel paymentId={} xid={} driven={} reason={}", paymentId, xid, driven, reason);
            }
            case DRIVE -> {
                if (manual) {
                    coordinator.drive(xid);
                } else {
                    // TRYING/CONFIRMING/CANCELLING 属既有崩溃恢复矩阵：仍由 recover 按原退避/重试预算续驱，
                    // 补单 job 不重复驱动（避免双倍消耗 retry_count 把可恢复事务提前推成 DEAD）
                    log.info("补单交由 TCC recover 续驱 paymentId={} xid={} dtx={}", paymentId, xid, dtx);
                }
            }
            case WAIT -> log.info("补单等待渠道终态（退避后再查）paymentId={} channel={}", paymentId, channelStatus);
            case MANUAL -> log.error("补单需人工/差错介入 paymentId={} xid={} dtx={} channel={}",
                    paymentId, xid, dtx, channelStatus);
            case NOOP -> log.warn("补单不动作：dtx 已终态但支付单仍在途 paymentId={} xid={} dtx={}", paymentId, xid, dtx);
        }
        return reload(paymentId);
    }

    private PaymentDTO reload(String paymentId) {
        return PaymentConverter.toDTO(paymentMapper.selectById(paymentId));
    }
}