package com.qoder.payment.channel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.payment.channel.config.ChannelConfigKeys;
import com.qoder.payment.channel.dataobject.SandboxOrderDO;
import com.qoder.payment.channel.mapper.SandboxOrderMapper;
import com.qoder.payment.channel.spi.ChannelFaultInjector;
import com.qoder.payment.channel.spi.ChannelHttpClient;
import com.qoder.payment.channel.spi.SandboxBankAdapter;
import com.qoder.payment.common.config.DynamicConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CH-2b 沙箱银行「回执推送」job（模拟渠道侧行为，与平台补单 job 互为对手盘）。
 *
 * <p>到期受理单先落渠道侧终态（reject 注入 → FAILED，否则 CAPTURED/REFUNDED），
 * 再经真实 HTTP + HMAC 签名推送到平台 {@code /channel-ops/sandbox/receipt}：
 * <ul>
 *   <li>回执丢失注入（receiptLoss）→ 只落终态不推送，平台必须靠查询补单收敛；</li>
 *   <li>金额不符注入 → 平台回执护栏拒绝（400），重试耗尽后建差错单（AMOUNT_MISMATCH）并停止重试，
 *       <b>绝不改写平台状态</b>（资金护航：金额不一致不得过账）；</li>
 *   <li>平台无受理记录（404）→ 标记已送达（无需收敛）；IO/5xx → 下一轮重试。</li>
 * </ul>
 * 本 job 只写沙箱自有账本 sandbox_order，不触碰 channel_trade / 支付 / 账务任何既有数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxReceiptJob {

    private static final int MAX_PUSH_ATTEMPTS = 5;

    private final SandboxOrderMapper orderMapper;
    private final ChannelHttpClient http;
    private final ChannelFaultInjector faults;
    private final DynamicConfig config;
    private final ChannelDisputeReporter disputeReporter;
    private final ObjectMapper json = new ObjectMapper();

    @Scheduled(fixedDelay = 500)
    public void deliver() {
        if (!config.getBool(ChannelConfigKeys.SANDBOX_RECEIPT_ENABLED, ChannelConfigKeys.DEFAULT_SANDBOX_RECEIPT_ENABLED)) {
            return;
        }
        long now = System.currentTimeMillis();
        finalizeDue(now);
        pushPending(now);
    }

    /** 渠道侧终态落地（延迟注入由 due_at 体现）。 */
    private void finalizeDue(long now) {
        List<SandboxOrderDO> due = orderMapper.selectDue(now, 50);
        for (SandboxOrderDO order : due) {
            boolean declined = faults.isReject() && faults.applies(order.getBizNo());
            String to = declined ? "FAILED" : ("REFUND".equals(order.getType()) ? "REFUNDED" : "CAPTURED");
            int updated = orderMapper.casStatus(order.getId(), "SUBMITTED", to, now);
            if (updated == 1) {
                log.info("沙箱银行落终态 bizNo={} status={} amount={} receiptLoss={}",
                        order.getBizNo(), to, order.getAmount(), faults.isReceiptLoss());
            }
        }
    }

    private void pushPending(long now) {
        for (SandboxOrderDO order : orderMapper.selectPendingReceipt(now, 50)) {
            if (faults.isReceiptLoss() && faults.applies(order.getBizNo())) {
                continue;
            }
            push(order);
        }
    }

    private void push(SandboxOrderDO order) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bizNo", order.getBizNo());
        body.put("channelOrderNo", order.getChannelOrderNo());
        body.put("status", order.getStatus());
        body.put("amount", order.getAmount());
        body.put("reason", "FAILED".equals(order.getStatus()) ? "沙箱渠道拒绝（故障注入 reject）" : null);
        String payload;
        try {
            payload = json.writeValueAsString(body);
        } catch (Exception e) {
            log.error("沙箱回执序列化失败 bizNo={}: {}", order.getBizNo(), e.getMessage());
            return;
        }
        String url = baseUrl() + "/channel-ops/sandbox/receipt";
        ChannelHttpClient.HttpResult response = http.postJson(url, payload, SandboxBankAdapter.SECRET_REF, 3000L);
        orderMapper.incrAttempts(order.getId());
        if (response.ok()) {
            orderMapper.markReceiptSent(order.getId());
            log.info("沙箱回执已送达 bizNo={} status={}", order.getBizNo(), order.getStatus());
            return;
        }
        if (response.status() == 404) {
            // 平台无此受理记录（例如仅渠道侧压测单）：无需收敛
            orderMapper.markReceiptSent(order.getId());
            log.info("沙箱回执：平台无受理记录，跳过 bizNo={}", order.getBizNo());
            return;
        }
        int attempts = (order.getAttempts() == null ? 0 : order.getAttempts()) + 1;
        if (response.status() == 400) {
            if (attempts >= MAX_PUSH_ATTEMPTS) {
                disputeReporter.report(ChannelDisputeReporter.CHECK_ASYNC, order.getBizDate(),
                        "AMOUNT_MISMATCH", order.getBizNo(), order.getBizNo(), order.getAmount(), "MANUAL",
                        "沙箱回执被平台护栏拒绝（金额不符/状态不匹配），重试 " + attempts + " 次仍未落地: " + response.body());
                orderMapper.updateAmount(order.getId(), order.getAmount() == null ? 0L : order.getAmount(),
                        order.getFault() + ",DISPUTED");
                // 停止重试（receipt_sent=2）：金额不一致不得过账，留人工核对
                orderMapper.markDisputed(order.getId());
            }
            return;
        }
        if (attempts >= MAX_PUSH_ATTEMPTS * 4) {
            disputeReporter.report(ChannelDisputeReporter.CHECK_ASYNC, order.getBizDate(),
                    "SOURCE_UNAVAILABLE", order.getBizNo(), order.getBizNo(), order.getAmount(), "MANUAL",
                    "沙箱回执持续不可达（重试 " + attempts + " 次），平台需查询补单收敛");
            orderMapper.markDisputed(order.getId());
        }
    }

    private String baseUrl() {
        String url = config.get(ChannelConfigKeys.SANDBOX_BASE_URL, ChannelConfigKeys.DEFAULT_SANDBOX_BASE_URL);
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}