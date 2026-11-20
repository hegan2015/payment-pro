package com.qoder.payment.payment.service;

import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.tcc.TccContextUtil;
import com.qoder.payment.facade.notify.NotifyFacade;
import com.qoder.payment.facade.notify.NotifyRequest;
import com.qoder.payment.facade.settle.SettleFacade;
import com.qoder.payment.facade.settle.SettlePayRequest;
import com.qoder.payment.facade.settle.SettleRefundRequest;
import com.qoder.payment.facade.settle.ShareSettleRequest;
import com.qoder.payment.payment.dataobject.OutboxDO;
import com.qoder.payment.payment.mapper.OutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 本地消息表可靠投递：至少一次语义 + 接收端幂等 = 恰好一次效果。
 * 指数退避重试，耗尽置 DEAD 等待对账/人工介入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxDispatcher {

    private static final int[] BACKOFF_SECONDS = {3, 6, 12, 24, 48, 96, 192, 384};
    private static final int MAX_ATTEMPTS = 8;

    private final OutboxMapper outboxMapper;
    /** P-3 分账投递进度回调（additive：仅 SHARE_* 事件生效，既有事件路径不变）。 */
    private final ProfitShareService shareService;

    @DubboReference
    private SettleFacade settleFacade;

    @DubboReference
    private NotifyFacade notifyFacade;

    /**
     * I-3：轮询兜底投递加分布式锁（锁名 dispatchOutbox，与 XXL-Job JobHandler 同名），
     * 多实例只有一个执行，防 E-6 重复驱动产生重复 outbox 投递。
     * lockAtLeastFor=0：3s 轮询节拍不被拖慢（outbox 兜底永不删除、投递不得停摆，红线）；
     * lockAtMostFor 覆盖最坏一轮（50 条 × 下游超时），进程猝死由 ShedLockOrphanRecovery 启动即回收。
     * <p>MQ 主投递入口 {@link #deliverByEventBizId} 不加锁（按 event+bizId 定位单行 + 接收端幂等），
     * 与轮询兜底双投由接收端 IdempotentTemplate 吸收。
     */
    @SchedulerLock(name = "dispatchOutbox", lockAtMostFor = "PT2M", lockAtLeastFor = "PT0S")
    public void dispatchPending() {
        List<OutboxDO> pending = outboxMapper.selectPending(System.currentTimeMillis(), 50);
        for (OutboxDO message : pending) {
            try {
                deliver(message);
                outboxMapper.markDelivered(message.getId());
            } catch (Exception e) {
                int attempts = message.getAttempts() + 1;
                String error = e.getMessage();
                if (attempts >= MAX_ATTEMPTS) {
                    outboxMapper.markDead(message.getId(), error);
                    log.error("outbox 投递重试耗尽置 DEAD id={} event={}: {}", message.getId(), message.getEvent(), error);
                    MetricsSupport.outboxDead(message.getEvent());
                } else {
                    long delay = BACKOFF_SECONDS[Math.min(attempts - 1, BACKOFF_SECONDS.length - 1)] * 1000L;
                    outboxMapper.markRetry(message.getId(), attempts, System.currentTimeMillis() + delay, error);
                    log.warn("outbox 投递待重试 id={} event={} attempts={}: {}", message.getId(), message.getEvent(), attempts, error);
                }
            }
        }
    }

    private void deliver(OutboxDO message) {
        String payload = message.getPayload();
        switch (message.getEvent()) {
            case "SETTLE_PAY" -> settleFacade.recordPay(TccContextUtil.fromJson(payload, SettlePayRequest.class)).orThrow();
            case "SETTLE_REFUND" -> settleFacade.recordRefund(TccContextUtil.fromJson(payload, SettleRefundRequest.class)).orThrow();
            case "NOTIFY_PAY", "NOTIFY_REFUND" -> notifyFacade.send(TccContextUtil.fromJson(payload, NotifyRequest.class)).orThrow();
            // P-3 分账（PP_SHARE topic）：settle 登记动账（(SETTLE_SHARE,shareId:receiver) 幂等）
            // → 明细/整单状态 CAS 推进 → 终态写 SHARE_NOTIFY outbox（SHARE_SUCCESS/SHARE_BACK 事件）。
            case "SHARE_SETTLE" -> {
                ShareSettleRequest request = TccContextUtil.fromJson(payload, ShareSettleRequest.class);
                settleFacade.recordShare(request).orThrow();
                shareService.markDetailSettled(request.getShareId(), request.getReceiverAccountId());
            }
            case "SHARE_SETTLE_BACK" -> {
                ShareSettleRequest request = TccContextUtil.fromJson(payload, ShareSettleRequest.class);
                settleFacade.recordShareBack(request).orThrow();
                shareService.markDetailBacked(request.getShareId(), request.getReceiverAccountId());
            }
            case "SHARE_NOTIFY", "SHARE_NOTIFY_BACK" ->
                    notifyFacade.send(TccContextUtil.fromJson(payload, NotifyRequest.class)).orThrow();
            default -> throw new IllegalStateException("未知 outbox 事件: " + message.getEvent());
        }
    }

    /**
     * MQ 主投递入口：按 event+bizId 定位 PENDING 行并投递（接收端幂等）。
     * 已 DELIVERED/DEAD 或不存在则跳过；投递失败抛出由 broker 重投，轮询兜底亦会处理。
     */
    public void deliverByEventBizId(String event, String bizId) {
        OutboxDO message = outboxMapper.selectByEventBizId(event, bizId);
        if (message == null || !"PENDING".equals(message.getStatus())) {
            return;
        }
        deliver(message);
        outboxMapper.markDelivered(message.getId());
    }
}