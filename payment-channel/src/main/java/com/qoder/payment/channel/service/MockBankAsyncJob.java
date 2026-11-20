package com.qoder.payment.channel.service;

import com.qoder.payment.channel.dataobject.ChannelTradeDO;
import com.qoder.payment.channel.mapper.ChannelTradeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模拟银行「异步落地终态」 job（CH-1 混沌靶，仅 {@code payment.channel.async=true} 生效）。
 *
 * <p>模拟银行侧在受理若干毫秒后自行完成扣款/拒绝，但<b>不推送回执</b>——
 * 等价于「回执丢失」场景：支付侧必须靠主动查询补单才能收敛终态。
 * 卡号尾号 9999 模拟银行异步拒绝（FAILED），其余落地 CAPTURED。
 *
 * <p>同步模式（默认）下本 job 立即返回，不做任何事，smoke/verify 行为不变。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockBankAsyncJob {

    private final ChannelTradeMapper channelTradeMapper;

    @Value("${payment.channel.async:false}")
    private boolean async;

    @Value("${payment.channel.async-delay-ms:3000}")
    private long delayMs;

    @Value("${payment.channel.async-batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelay = 1000)
    public void finalizeSubmitted() {
        if (!async) {
            return;
        }
        long now = System.currentTimeMillis();
        List<ChannelTradeDO> due = channelTradeMapper.selectSubmittedBefore(now - delayMs, batchSize);
        for (ChannelTradeDO trade : due) {
            boolean declined = trade.getCardNo() != null && trade.getCardNo().endsWith("9999");
            String to = declined ? "FAILED" : "CAPTURED";
            String reason = declined ? "渠道异步拒绝: 银行风控拦截 (卡号尾号 9999)" : null;
            int updated = channelTradeMapper.casTerminal(trade.getBizNo(), "SUBMITTED", to, reason, now);
            if (updated == 1) {
                log.info("模拟银行异步落地终态(未推回执) bizNo={} status={}", trade.getBizNo(), to);
            }
        }
    }
}