package com.qoder.payment.payment.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.notify.NotifyRequest;
import com.qoder.payment.facade.payment.ShareBackRequest;
import com.qoder.payment.facade.payment.ShareOrderDTO;
import com.qoder.payment.facade.payment.ShareRequest;
import com.qoder.payment.facade.settle.SettleFacade;
import com.qoder.payment.facade.settle.ShareSettleRequest;
import com.qoder.payment.payment.dataobject.PaymentDO;
import com.qoder.payment.payment.dataobject.ShareDetailDO;
import com.qoder.payment.payment.dataobject.ShareOrderDO;
import com.qoder.payment.payment.mapper.OutboxMapper;
import com.qoder.payment.payment.mapper.PaymentMapper;
import com.qoder.payment.payment.mapper.ShareDetailMapper;
import com.qoder.payment.payment.mapper.ShareOrderMapper;
import com.qoder.payment.payment.mq.MqProperties;
import com.qoder.payment.payment.mq.OutboxPublisher;
import com.qoder.payment.payment.mq.TopicRouter;
import com.qoder.tcc.TccContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P-3 分账编排（payment 域）：比例/固定额计算 → share_order/share_detail 建单 →
 * 经事务性 outbox（PP_SHARE topic）投递 settle 域 {@code SettleShareService} 登记动账。
 *
 * <p><b>与 D 泳道 ST-3 边界</b>：本域算比例建单与编排重试；settle 只收结果做
 * 「守恒校验 → 登记明细 → 复式记账划转」（(SETTLE_SHARE,shareId:receiver) 幂等，
 * 账务 bizNo 确定性派生 SS:/SB:），不建 share_order 不定比例。
 *
 * <p><b>资金护航</b>：
 * <ul>
 *   <li>可分额 = 支付净额(amount−fee) − 已分账占用（PENDING/SHARED/BACKING 单合计），超额拒绝；</li>
 *   <li>全额分账 Σ=净额误差 0（settle shareSummary.diff==0 可验）；</li>
 *   <li>幂等：share_order.request_id UNIQUE + 明细 UNIQUE(share_id,receiver) + settle 登记幂等
 *       + 状态 CAS（PENDING→SHARED→BACKING→BACKED 只翻一次）；</li>
 *   <li>outbox 仍是唯一事实来源：SHARE_SETTLE/SHARE_SETTLE_BACK 投递失败指数退避重试，
 *       耗尽 DEAD 后由 shareExecute 作业兜底直驱（同一幂等键，不重复动账）；</li>
 *   <li>回退整单反向划转（settle recordShareBack），已回退拒绝重复；</li>
 *   <li>延迟分账：outbox next_retry_at=到期时间（local 纯轮询等价路径），到期作业兜底直驱。</li>
 * </ul>
 *
 * <p>事件生产者（N-3 已登记事件名）：分账终态写 SHARE_NOTIFY(SHARE_SUCCESS) /
 * SHARE_NOTIFY_BACK(SHARE_BACK) outbox，经 NotifyFacade 投递（envelope 新事件，冻结事件不受影响）。
 */
@Slf4j
@Service
public class ProfitShareService {

    private static final long EXECUTE_BACKOFF_MS = 30_000L;

    private final ShareOrderMapper shareOrderMapper;
    private final ShareDetailMapper shareDetailMapper;
    private final PaymentMapper paymentMapper;
    private final OutboxMapper outboxMapper;
    private final MqProperties mqProps;
    private final TopicRouter topicRouter;
    private final ObjectProvider<OutboxPublisher> publisherProvider;
    private final TransactionTemplate tx;

    @DubboReference
    private SettleFacade settleFacade;

    @DubboReference
    private AccountFacade accountFacade;

    public ProfitShareService(ShareOrderMapper shareOrderMapper, ShareDetailMapper shareDetailMapper,
                              PaymentMapper paymentMapper, OutboxMapper outboxMapper, MqProperties mqProps,
                              TopicRouter topicRouter, ObjectProvider<OutboxPublisher> publisherProvider,
                              PlatformTransactionManager txManager) {
        this.shareOrderMapper = shareOrderMapper;
        this.shareDetailMapper = shareDetailMapper;
        this.paymentMapper = paymentMapper;
        this.outboxMapper = outboxMapper;
        this.mqProps = mqProps;
        this.topicRouter = topicRouter;
        this.publisherProvider = publisherProvider;
        this.tx = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------ 受理

    public ShareOrderDTO share(ShareRequest request) {
        if (request == null || request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "requestId 必填（幂等键）");
        }
        ShareOrderDO replay = shareOrderMapper.selectByRequestId(request.getRequestId());
        if (replay != null) {
            log.info("分账幂等命中 requestId={} shareId={}", request.getRequestId(), replay.getId());
            return toDTO(replay);
        }
        if (request.getPaymentId() == null || request.getPaymentId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "paymentId 必填");
        }
        PaymentDO payment = paymentMapper.selectById(request.getPaymentId());
        if (payment == null) {
            throw new BizException(ResultCode.NOT_FOUND, "支付单不存在: " + request.getPaymentId());
        }
        if (!"SUCCESS".equals(payment.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "支付单未成功，不可分账: " + payment.getStatus());
        }
        if (!"TRADE".equals(payment.getBizType())) {
            throw new BizException(ResultCode.BIZ_ERROR, "资金单不支持分账");
        }
        if (payment.getRefundedAmount() != null && payment.getRefundedAmount() > 0) {
            throw new BizException(ResultCode.CONFLICT, "已退款支付单不可分账（保守口径）");
        }
        List<ShareRequest.Receiver> receivers = request.getReceivers();
        if (receivers == null || receivers.isEmpty() || receivers.size() > 20) {
            throw new BizException(ResultCode.PARAM_ERROR, "receivers 必填且不超过 20 方");
        }
        long net = payment.getAmount() - payment.getFee();
        Set<String> seen = new HashSet<>();
        List<long[]> amounts = new ArrayList<>();
        long sum = 0;
        for (ShareRequest.Receiver receiver : receivers) {
            if (receiver == null || receiver.getAccountId() == null || receiver.getAccountId().isBlank()) {
                throw new BizException(ResultCode.PARAM_ERROR, "分账收款账户必填");
            }
            if (!seen.add(receiver.getAccountId())) {
                throw new BizException(ResultCode.PARAM_ERROR, "分账收款账户重复: " + receiver.getAccountId());
            }
            if (receiver.getAccountId().equals(payment.getMerchantAccountId())) {
                throw new BizException(ResultCode.PARAM_ERROR, "收款账户不得与出账商户账户相同");
            }
            AccountDTO account = accountFacade.get(receiver.getAccountId()).orThrow();
            if (!"ACTIVE".equals(account.getStatus())) {
                throw new BizException(ResultCode.BIZ_ERROR, "分账收款账户已冻结: " + receiver.getAccountId());
            }
            long amount;
            if (receiver.getAmount() != null) {
                MoneyUtil.requireValid(receiver.getAmount());
                amount = receiver.getAmount();
            } else if (receiver.getRatioBps() != null && receiver.getRatioBps() > 0 && receiver.getRatioBps() <= 10000) {
                amount = net * receiver.getRatioBps() / 10000;
                if (amount <= 0) {
                    throw new BizException(ResultCode.PARAM_ERROR, "比例分账额为 0: " + receiver.getAccountId());
                }
            } else {
                throw new BizException(ResultCode.PARAM_ERROR, "分账额 amount 或 ratioBps(1~10000) 二选一");
            }
            amounts.add(new long[]{amount});
            sum += amount;
        }
        long allocated = shareOrderMapper.sumActiveAllocated(payment.getId());
        if (allocated + sum > net) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "分账超额: 已分 " + allocated + " + 本次 " + sum + " > 可分净额 " + net);
        }
        String shareType = "DELAYED".equals(request.getShareType()) ? "DELAYED" : "REALTIME";
        long now = System.currentTimeMillis();
        long executeAt = shareType.equals("DELAYED")
                ? now + (request.getDelayMs() != null && request.getDelayMs() > 0 ? request.getDelayMs() : 60_000L)
                : now;

        ShareOrderDO order = new ShareOrderDO();
        order.setId("SH" + shareOrderMapper.nextId());
        order.setPaymentId(payment.getId());
        order.setMerchantId(payment.getMerchantId());
        order.setMerchantAccountId(payment.getMerchantAccountId());
        order.setTotalAmount(sum);
        order.setShareType(shareType);
        order.setStatus("PENDING");
        order.setRequestId(request.getRequestId());
        order.setBizNo(order.getId());
        order.setExecuteAt(executeAt);
        order.setCreatedAt(now);
        List<ShareDetailDO> details = new ArrayList<>();
        for (int i = 0; i < receivers.size(); i++) {
            ShareDetailDO detail = new ShareDetailDO();
            detail.setId("SD" + shareOrderMapper.nextId());
            detail.setShareId(order.getId());
            detail.setReceiverAccountId(receivers.get(i).getAccountId());
            detail.setAmount(amounts.get(i)[0]);
            detail.setStatus("PENDING");
            detail.setBizNo("SS:" + order.getId() + ":" + receivers.get(i).getAccountId());
            detail.setCreatedAt(now);
            details.add(detail);
        }
        // 建单 + 明细 + outbox 同一本地事务（事务性 outbox，PP_SHARE 路由）
        List<Runnable> publishes = new ArrayList<>();
        long nextRetryAt = shareType.equals("DELAYED") ? executeAt : (mqProps.isEnabled() ? now + mqProps.getFallbackGraceMs() : 0L);
        tx.executeWithoutResult(s -> {
            try {
                shareOrderMapper.insert(order);
            } catch (DuplicateKeyException e) {
                throw new BizException(ResultCode.CONFLICT, "分账幂等冲突，请重试: " + request.getRequestId());
            }
            for (ShareDetailDO detail : details) {
                shareDetailMapper.insert(detail);
                String payload = TccContextUtil.toJson(settleRequest(order, detail));
                outboxMapper.insertRouted("SHARE_SETTLE", detail.getShareId() + ":" + detail.getReceiverAccountId(),
                        payload, nextRetryAt, now, topicRouter.route("SHARE_SETTLE"));
                if (shareType.equals("REALTIME")) {
                    capture(publishes, "SHARE_SETTLE", detail.getShareId() + ":" + detail.getReceiverAccountId(), payload);
                }
            }
        });
        runAfterCommit(publishes);
        log.info("分账受理 shareId={} paymentId={} total={} net={} type={} receivers={} executeAt={}",
                order.getId(), payment.getId(), sum, net, shareType, receivers.size(), executeAt);
        return toDTO(order);
    }

    // ------------------------------------------------------------------ 执行驱动（outbox 投递回调 / 作业兜底 / 手动）

    /** outbox SHARE_SETTLE 投递成功后回调：明细 CAS SHARED，全齐则整单 SHARED + 通知 outbox。 */
    public void markDetailSettled(String shareId, String receiverAccountId) {
        List<Runnable> publishes = new ArrayList<>();
        tx.executeWithoutResult(s -> {
            shareDetailMapper.markShared(shareId, receiverAccountId);
            completeIfDone(shareId, publishes);
        });
        runAfterCommit(publishes);
    }

    /** outbox SHARE_SETTLE_BACK 投递成功后回调：明细 CAS BACKED，全齐则整单 BACKED + 通知 outbox。 */
    public void markDetailBacked(String shareId, String receiverAccountId) {
        List<Runnable> publishes = new ArrayList<>();
        tx.executeWithoutResult(s -> {
            shareDetailMapper.markBacked(shareId, receiverAccountId);
            completeBackIfDone(shareId, publishes);
        });
        runAfterCommit(publishes);
    }

    /** 手动/作业直驱（幂等）：逐明细调 settle 登记（同一幂等键），失败退避 30s 后可重试。 */
    public ShareOrderDTO execute(String shareId) {
        ShareOrderDO order = require(shareId);
        if ("PENDING".equals(order.getStatus())) {
            if (order.getExecuteAt() != null && order.getExecuteAt() > System.currentTimeMillis()) {
                return toDTO(order);
            }
            for (ShareDetailDO detail : shareDetailMapper.selectByShareId(shareId)) {
                if (!"PENDING".equals(detail.getStatus())) {
                    continue;
                }
                try {
                    settleFacade.recordShare(settleRequest(order, detail)).orThrow();
                    markDetailSettled(shareId, detail.getReceiverAccountId());
                } catch (Exception e) {
                    log.warn("分账执行失败待重试 shareId={} receiver={}: {}", shareId, detail.getReceiverAccountId(), e.getMessage());
                    shareOrderMapper.updateFailReason(shareId, truncate(e.getMessage()));
                    shareOrderMapper.postpone(shareId, System.currentTimeMillis() + EXECUTE_BACKOFF_MS);
                    return toDTO(require(shareId));
                }
            }
            // 明细可能已由 outbox 并发投递全部 SHARED（并发完成竞态窗口）：兜底再判整单完成
            finishIfComplete(shareId);
        } else if ("BACKING".equals(order.getStatus())) {
            driveBack(order);
        }
        return toDTO(require(shareId));
    }

    /** BACKING 单直驱回退（作业兜底，幂等）。 */
    public void driveBack(ShareOrderDO order) {
        for (ShareDetailDO detail : shareDetailMapper.selectByShareId(order.getId())) {
            if (!"SHARED".equals(detail.getStatus())) {
                continue;
            }
            try {
                settleFacade.recordShareBack(settleRequest(order, detail)).orThrow();
                markDetailBacked(order.getId(), detail.getReceiverAccountId());
            } catch (Exception e) {
                log.warn("分账回退失败待重试 shareId={} receiver={}: {}", order.getId(), detail.getReceiverAccountId(), e.getMessage());
                shareOrderMapper.updateFailReason(order.getId(), truncate(e.getMessage()));
                return;
            }
        }
        finishIfComplete(order.getId());
    }

    /**
     * 整单完成兜底判定（事务内 CAS）：并发投递下两明细可能互相看到对方未提交而都跳过完成判定，
     * 作业/直驱路径经本方法收敛（PENDING→SHARED / BACKING→BACKED 只翻一次，通知 outbox 不重写）。
     */
    private void finishIfComplete(String shareId) {
        List<Runnable> publishes = new ArrayList<>();
        tx.executeWithoutResult(s -> {
            completeIfDone(shareId, publishes);
            completeBackIfDone(shareId, publishes);
        });
        runAfterCommit(publishes);
    }

    // ------------------------------------------------------------------ 回退

    public ShareOrderDTO shareBack(ShareBackRequest request) {
        if (request == null || request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "requestId 必填（幂等键）");
        }
        if (request.getShareId() == null || request.getShareId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "shareId 必填");
        }
        ShareOrderDO order = require(request.getShareId());
        if ("BACKED".equals(order.getStatus()) || "BACKING".equals(order.getStatus())) {
            if (request.getRequestId().equals(order.getBackRequestId())) {
                return toDTO(order);
            }
            throw new BizException(ResultCode.CONFLICT, "分账已回退/回退中，拒绝重复回退: " + order.getId());
        }
        if (!"SHARED".equals(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "分账未完成，不可回退: " + order.getStatus());
        }
        long now = System.currentTimeMillis();
        long nextRetryAt = mqProps.isEnabled() ? now + mqProps.getFallbackGraceMs() : 0L;
        List<Runnable> publishes = new ArrayList<>();
        tx.executeWithoutResult(s -> {
            if (shareOrderMapper.markBacking(order.getId(), request.getRequestId()) != 1) {
                throw new BizException(ResultCode.CONFLICT, "分账状态已变更，请重试: " + order.getId());
            }
            for (ShareDetailDO detail : shareDetailMapper.selectByShareId(order.getId())) {
                String payload = TccContextUtil.toJson(settleRequest(order, detail));
                outboxMapper.insertRouted("SHARE_SETTLE_BACK", detail.getShareId() + ":" + detail.getReceiverAccountId(),
                        payload, nextRetryAt, now, topicRouter.route("SHARE_SETTLE_BACK"));
                capture(publishes, "SHARE_SETTLE_BACK", detail.getShareId() + ":" + detail.getReceiverAccountId(), payload);
            }
        });
        runAfterCommit(publishes);
        log.info("分账回退受理 shareId={} requestId={} total={}", order.getId(), request.getRequestId(), order.getTotalAmount());
        return toDTO(require(order.getId()));
    }

    // ------------------------------------------------------------------ 查询

    public ShareOrderDTO getShare(String shareId) {
        return toDTO(require(shareId));
    }

    public List<ShareOrderDTO> listShares(String paymentId) {
        return shareOrderMapper.selectByPaymentId(paymentId).stream().map(this::toDTO).toList();
    }

    // ------------------------------------------------------------------ 内部

    private void completeIfDone(String shareId, List<Runnable> publishes) {
        if (shareDetailMapper.countByStatus(shareId, "PENDING") > 0) {
            return;
        }
        ShareOrderDO order = shareOrderMapper.selectById(shareId);
        if (order == null || !"PENDING".equals(order.getStatus())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (shareOrderMapper.markShared(shareId, now) == 1) {
            insertNotify(order, "SHARE_NOTIFY", "SHARE_SUCCESS", publishes);
            log.info("分账完成 shareId={} paymentId={} total={}", shareId, order.getPaymentId(), order.getTotalAmount());
        }
    }

    private void completeBackIfDone(String shareId, List<Runnable> publishes) {
        if (shareDetailMapper.countByStatus(shareId, "SHARED") > 0
                || shareDetailMapper.countByStatus(shareId, "PENDING") > 0) {
            return;
        }
        ShareOrderDO order = shareOrderMapper.selectById(shareId);
        if (order == null || !"BACKING".equals(order.getStatus())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (shareOrderMapper.markBacked(shareId, now) == 1) {
            insertNotify(order, "SHARE_NOTIFY_BACK", "SHARE_BACK", publishes);
            log.info("分账回退完成 shareId={} total={}", shareId, order.getTotalAmount());
        }
    }

    /** 分账终态事件（N-3 已登记 SHARE_SUCCESS/SHARE_BACK，envelope 新事件，冻结事件不受影响）。 */
    private void insertNotify(ShareOrderDO order, String outboxEvent, String notifyEvent, List<Runnable> publishes) {
        PaymentDO payment = paymentMapper.selectById(order.getPaymentId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", order.getPaymentId());
        payload.put("shareId", order.getId());
        payload.put("amount", order.getTotalAmount());
        NotifyRequest notify = new NotifyRequest();
        notify.setEvent(notifyEvent);
        notify.setBizId(order.getId());
        notify.setPayload(payload);
        notify.setCallbackUrl(payment == null ? null : payment.getCallbackUrl());
        String json = TccContextUtil.toJson(notify);
        long now = System.currentTimeMillis();
        long nextRetryAt = mqProps.isEnabled() ? now + mqProps.getFallbackGraceMs() : 0L;
        outboxMapper.insertRouted(outboxEvent, order.getId(), json, nextRetryAt, now, topicRouter.route(outboxEvent));
        capture(publishes, outboxEvent, order.getId(), json);
    }

    private static ShareSettleRequest settleRequest(ShareOrderDO order, ShareDetailDO detail) {
        ShareSettleRequest request = new ShareSettleRequest();
        request.setShareId(order.getId());
        request.setPaymentId(order.getPaymentId());
        request.setMerchantId(order.getMerchantId());
        request.setMerchantAccountId(order.getMerchantAccountId());
        request.setReceiverAccountId(detail.getReceiverAccountId());
        request.setAmount(detail.getAmount());
        request.setFee(0L);
        return request;
    }

    private ShareOrderDO require(String shareId) {
        ShareOrderDO order = shareOrderMapper.selectById(shareId);
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND, "分账单不存在: " + shareId);
        }
        return order;
    }

    private ShareOrderDTO toDTO(ShareOrderDO order) {
        ShareOrderDTO dto = new ShareOrderDTO();
        dto.setId(order.getId());
        dto.setPaymentId(order.getPaymentId());
        dto.setMerchantId(order.getMerchantId());
        dto.setMerchantAccountId(order.getMerchantAccountId());
        dto.setTotalAmount(order.getTotalAmount());
        dto.setShareType(order.getShareType());
        dto.setStatus(order.getStatus());
        dto.setRequestId(order.getRequestId());
        dto.setBizNo(order.getBizNo());
        dto.setExecuteAt(order.getExecuteAt());
        dto.setCreatedAt(order.getCreatedAt());
        dto.setSharedAt(order.getSharedAt());
        dto.setBackedAt(order.getBackedAt());
        dto.setFailReason(order.getFailReason());
        List<ShareOrderDTO.Detail> details = new ArrayList<>();
        for (ShareDetailDO detail : shareDetailMapper.selectByShareId(order.getId())) {
            ShareOrderDTO.Detail d = new ShareOrderDTO.Detail();
            d.setId(detail.getId());
            d.setReceiverAccountId(detail.getReceiverAccountId());
            d.setAmount(detail.getAmount());
            d.setStatus(detail.getStatus());
            d.setBizNo(detail.getBizNo());
            details.add(d);
        }
        dto.setDetails(details);
        return dto;
    }

    private void capture(List<Runnable> publishes, String event, String bizId, String payload) {
        if (!mqProps.isEnabled()) {
            return;
        }
        OutboxPublisher publisher = publisherProvider.getIfAvailable();
        if (publisher != null) {
            publishes.add(() -> publisher.publish(event, bizId, payload));
        }
    }

    private static void runAfterCommit(List<Runnable> publishes) {
        if (publishes.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishes.forEach(Runnable::run);
                }
            });
        } else {
            publishes.forEach(Runnable::run);
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 256 ? message : message.substring(0, 256);
    }
}