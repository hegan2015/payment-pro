package com.qoder.payment.payment.service;

import com.qoder.payment.common.db.ShardKeys;
import com.qoder.payment.common.db.ShardRowMirror;
import com.qoder.payment.common.db.ShardRuntime;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.payment.dataobject.PaymentDO;
import com.qoder.payment.payment.dataobject.RefundDO;
import com.qoder.payment.payment.mapper.PaymentMapper;
import com.qoder.payment.payment.mapper.RefundMapper;
import com.qoder.payment.payment.mapper.RequestIndexMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * I-5b 支付/退款受理落库闸门：<b>跨片全局幂等</b>（§0.5 E-7 核心）。
 *
 * <p><b>单库态（含 local 与一键回滚后）</b>：路径与改造前逐字节一致 ——
 * 直接 INSERT，靠 {@code uk_payment_request} / {@code uk_refund_request} 全局唯一键兜底，
 * 冲突即 {@code selectByRequestId} 幂等回放。
 *
 * <p><b>分片态</b>：payment 按 id 分片、refund_record 按 payment_id 分片，片内唯一键无法保证全局幂等。
 * 改为「<b>先 insert 单库不分片的索引占位，再写业务单</b>」，且两步在<b>同一本地事务</b>内：
 * <ul>
 *   <li>单库分表方案下索引表与分片表同属一个 MySQL 库、同一连接 ⇒ 占位与业务单原子提交；</li>
 *   <li>并发败者在索引主键上阻塞，胜者提交后败者拿到既有 id 直接回放（不会看到「占位存在但单不存在」）；</li>
 *   <li>索引存在而业务单不可见时<b>失败关闭</b>（CONFLICT），绝不重复受理产生第二笔资金单。</li>
 * </ul>
 *
 * <p><b>ID 形态未变</b>：paymentId/refundId 仍为 {@code P/R + 十进制数字}，不嵌分片基因，
 * 存量 id 与 ride-pro 双账本完全兼容（详见 {@link RequestIndexMapper}）。
 */
public class PaymentRequestIndex {

    private static final Logger log = LoggerFactory.getLogger(PaymentRequestIndex.class);

    private final RequestIndexMapper indexMapper;
    private final PaymentMapper paymentMapper;
    private final RefundMapper refundMapper;
    private final ShardRuntime runtime;
    private final ShardRowMirror mirror;
    private final TransactionTemplate tx;

    public PaymentRequestIndex(RequestIndexMapper indexMapper, PaymentMapper paymentMapper, RefundMapper refundMapper,
                               ShardRuntime runtime, ShardRowMirror mirror, PlatformTransactionManager txManager) {
        this.indexMapper = indexMapper;
        this.paymentMapper = paymentMapper;
        this.refundMapper = refundMapper;
        this.runtime = runtime;
        this.mirror = mirror;
        this.tx = new TransactionTemplate(txManager);
    }

    public boolean sharded() {
        return runtime.isActive();
    }

    /**
     * 支付单落库。返回 {@code null}=本次首次受理（已落库）；非 {@code null}=幂等命中的既有支付单。
     */
    public PaymentDO insertPayment(PaymentDO payment) {
        if (!runtime.isActive()) {
            try {
                paymentMapper.insert(payment);
            } catch (DuplicateKeyException e) {
                return paymentMapper.selectByRequestId(payment.getRequestId());
            }
            mirror.mirror("payment", payment.getId());
            return null;
        }
        int shard = ShardKeys.shardOf(payment.getId(), runtime.shardCount());
        try {
            tx.executeWithoutResult(s -> {
                indexMapper.insertPayment(payment.getRequestId(), payment.getId(), shard, System.currentTimeMillis());
                paymentMapper.insert(payment);
            });
        } catch (DuplicateKeyException e) {
            String existingId = indexMapper.findPaymentId(payment.getRequestId());
            PaymentDO existing = existingId == null ? null : paymentMapper.selectById(existingId);
            if (existing == null) {
                existing = paymentMapper.selectByRequestId(payment.getRequestId());
            }
            if (existing == null) {
                log.error("I-5b 幂等占位已存在但支付单不可见（失败关闭，禁止重复受理）requestId={} indexPaymentId={}",
                        payment.getRequestId(), existingId);
                throw new BizException(ResultCode.CONFLICT,
                        "幂等占位已存在但支付单不可见，禁止重复受理: " + payment.getRequestId());
            }
            log.info("I-5b 跨片幂等命中（索引表）requestId={} paymentId={} shard={}",
                    payment.getRequestId(), existing.getId(), shard);
            return existing;
        }
        mirror.mirror("payment", payment.getId());
        return null;
    }

    /**
     * 退款单落库。返回 {@code null}=首次受理；非 {@code null}=幂等命中的既有退款单。
     */
    public RefundDO insertRefund(RefundDO refund) {
        if (!runtime.isActive()) {
            try {
                refundMapper.insert(refund);
            } catch (DuplicateKeyException e) {
                return refundMapper.selectByRequestId(refund.getRequestId());
            }
            mirror.mirror("refund_record", refund.getId());
            return null;
        }
        int shard = ShardKeys.shardOf(refund.getPaymentId(), runtime.shardCount());
        try {
            tx.executeWithoutResult(s -> {
                indexMapper.insertRefund(refund.getRequestId(), refund.getId(), refund.getPaymentId(),
                        shard, System.currentTimeMillis());
                refundMapper.insert(refund);
            });
        } catch (DuplicateKeyException e) {
            String existingId = indexMapper.findRefundId(refund.getRequestId());
            RefundDO existing = existingId == null ? null : refundMapper.selectById(existingId);
            if (existing == null) {
                existing = refundMapper.selectByRequestId(refund.getRequestId());
            }
            if (existing == null) {
                log.error("I-5b 退款幂等占位已存在但退款单不可见（失败关闭）requestId={} indexRefundId={}",
                        refund.getRequestId(), existingId);
                throw new BizException(ResultCode.CONFLICT,
                        "退款幂等占位已存在但退款单不可见，禁止重复受理: " + refund.getRequestId());
            }
            log.info("I-5b 退款跨片幂等命中（索引表）requestId={} refundId={}", refund.getRequestId(), existing.getId());
            return existing;
        }
        mirror.mirror("refund_record", refund.getId());
        return null;
    }

    public long paymentIndexRows() {
        return indexMapper.countPayment();
    }

    public long refundIndexRows() {
        return indexMapper.countRefund();
    }

    public String lookupPaymentId(String requestId) {
        return indexMapper.findPaymentId(requestId);
    }
}