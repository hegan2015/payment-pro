package com.qoder.payment.channel.service;

import com.qoder.payment.channel.dataobject.ChannelTradeDO;
import com.qoder.payment.channel.mapper.ChannelTradeMapper;
import com.qoder.payment.channel.spi.SandboxBankAdapter;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.facade.channel.ChannelReceiptRequest;
import com.qoder.payment.facade.channel.ChannelResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CH-2b 沙箱渠道「查询补单」job（平台侧，回执丢失/乱序/宕机的收敛兜底）。
 *
 * <p>仅 {@code payment.channel.async=true} 生效（同步 stack 立即返回，行为不变）。
 * 对 SUBMITTED 的沙箱渠道单主动 {@code adapter.query}：
 * <ul>
 *   <li>渠道终态 CAPTURED/REFUNDED/FAILED → 经 CH-1 {@link ChannelReceiptService} 落地（同一 CAS/去重/金额护栏），
 *       支付侧 PaymentReconcileJob 据此 resumeCommit/resumeCancel；</li>
 *   <li>查询不可达/仍受理中 → 等待（<b>不臆断失败</b>）；</li>
 *   <li>渠道明确答复 NOT_FOUND 且超过宽限期 → 确认未扣款 → FAILED 回执收敛（可安全回滚，无悬挂）；</li>
 *   <li>金额不符被护栏拒绝 → 重试耗尽建差错单（AMOUNT_MISMATCH），保留 SUBMITTED 留人工，绝不强行过账。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxQueryJob {

    private final ChannelTradeMapper tradeMapper;
    private final SandboxBankAdapter adapter;
    private final ChannelReceiptService receiptService;
    private final ChannelDisputeReporter disputeReporter;
    private final DynamicConfig config;

    @Value("${payment.channel.async:false}")
    private boolean async;

    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 1000)
    public void converge() {
        if (!async || !config.getBool("channel.sandbox.query.enabled", true)) {
            return;
        }
        long now = System.currentTimeMillis();
        long grace = config.getLong("channel.sandbox.query-grace-ms", 2000L);
        long notFoundGrace = config.getLong("channel.sandbox.not-found-grace-ms", 15000L);
        int maxAttempts = config.getInt("channel.sandbox.query-max-attempts", 5);
        List<ChannelTradeDO> pending = tradeMapper.selectSubmittedByChannel(SandboxBankAdapter.CODE, now - grace, 50);
        for (ChannelTradeDO trade : pending) {
            String bizNo = trade.getBizNo();
            ChannelResult result;
            try {
                result = adapter.query(bizNo);
            } catch (RuntimeException e) {
                log.warn("沙箱查询异常（等待下一轮）bizNo={}: {}", bizNo, e.getMessage());
                continue;
            }
            if (result == null || result.isProcessing()) {
                continue;
            }
            String channelStatus = result.getChannelStatus();
            if ("NOT_FOUND".equals(channelStatus)) {
                long age = now - (trade.getSubmittedAt() == null ? trade.getCreatedAt() : trade.getSubmittedAt());
                if (age < notFoundGrace) {
                    continue;
                }
                post(trade, "FAILED", null, "渠道无受理记录（查询确认未扣款，补单收敛）");
                continue;
            }
            if (!"CAPTURED".equals(channelStatus) && !"REFUNDED".equals(channelStatus) && !"FAILED".equals(channelStatus)) {
                continue;
            }
            try {
                post(trade, channelStatus, result.getAmount(), result.getReason());
                attempts.remove(bizNo);
            } catch (BizException e) {
                int n = attempts.merge(bizNo, 1, Integer::sum);
                log.warn("沙箱补单回执被护栏拒绝 bizNo={} attempt={} reason={}", bizNo, n, e.getMessage());
                if (n >= maxAttempts) {
                    disputeReporter.report(ChannelDisputeReporter.CHECK_ASYNC,
                            ChannelLimitService.bizDate(trade.getCreatedAt() == null ? now : trade.getCreatedAt()),
                            "AMOUNT_MISMATCH", bizNo, bizNo, trade.getAmount(), "MANUAL",
                            "沙箱渠道终态金额与平台流水不符，补单 " + n + " 次仍被拒绝: " + e.getMessage());
                    attempts.remove(bizNo);
                }
            }
        }
    }

    private void post(ChannelTradeDO trade, String status, Long amount, String reason) {
        ChannelReceiptRequest request = new ChannelReceiptRequest();
        request.setBizNo(trade.getBizNo());
        request.setStatus(status);
        request.setAmount(amount);
        request.setReason(reason);
        request.setRaw("sandbox-query-converge");
        receiptService.receiveReceipt(request);
        log.info("沙箱查询补单收敛 bizNo={} status={} amount={}", trade.getBizNo(), status, amount);
    }
}