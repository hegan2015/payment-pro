package com.qoder.payment.channel.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.payment.channel.dataobject.SandboxOrderDO;
import com.qoder.payment.channel.mapper.SandboxOrderMapper;
import com.qoder.payment.channel.service.ChannelLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CH-2b 沙箱银行「渠道侧」端点（模拟外部银行，本进程内，端口 18087 内部面，不经网关暴露）。
 *
 * <p><b>沙箱即沙箱</b>：这是模拟银行的自有账本（sandbox_order），不是真实银行接口，
 * 仓库内无任何真实渠道凭据；HTTP + HMAC 签名往返用于验证适配器通讯/验签/超时/重试语义。
 * 生产渠道接入时本控制器整体替换为真实渠道端点（同 SPI，不改平台侧代码）。
 *
 * <p>故障注入经 {@link ChannelFaultInjector}：down→503（确未受理，触发 CH-3 切换）、
 * timeoutMs→受理响应延迟（客户端读超时=状态未知）、reject→终态 FAILED、
 * amountMismatch→渠道侧金额与请求不符、receiptLoss→只落终态不推回执。
 */
@Slf4j
@RestController
@RequestMapping("/sandbox-bank")
@RequiredArgsConstructor
public class SandboxBankController {

    private final SandboxOrderMapper orderMapper;
    private final ChannelFaultInjector faults;
    private final ChannelHttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    @PostMapping("/submit")
    public ResponseEntity<Object> submit(@RequestBody String raw,
                                         @RequestHeader(value = "X-Channel-Sign", required = false) String sign,
                                         @RequestHeader(value = "X-Channel-Timestamp", required = false) String ts) {
        if (!http.verify(SandboxBankAdapter.SECRET_REF, ts, raw, sign)) {
            return ResponseEntity.status(401).body(Map.of("error", "渠道签名验签失败"));
        }
        JsonNode node = read(raw);
        String bizNo = node.path("bizNo").asText(null);
        String type = node.path("type").asText("PAY");
        long amount = node.path("amount").asLong(0L);
        if (bizNo == null || bizNo.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "bizNo 必填"));
        }
        if (faults.isDown()) {
            return ResponseEntity.status(503).body(Map.of("error", "沙箱银行宕机（故障注入 down）"));
        }
        if (faults.getTimeoutMs() > 0 && faults.applies(bizNo)) {
            sleep(faults.getTimeoutMs());
        }
        SandboxOrderDO existing = orderMapper.selectByBizNo(bizNo, type);
        if (existing != null) {
            // 幂等受理：同一 bizNo+type 重复提交返回既有渠道单（不重复扣款）
            return ResponseEntity.ok(view(existing));
        }
        long now = System.currentTimeMillis();
        boolean mismatch = faults.isAmountMismatch() && faults.applies(bizNo);
        SandboxOrderDO order = new SandboxOrderDO();
        order.setChannelOrderNo("SBX-" + type + "-" + bizNo);
        order.setBizNo(bizNo);
        order.setType(type);
        order.setRequestedAmount(amount);
        order.setAmount(mismatch ? amount + faults.getAmountDelta() : amount);
        order.setStatus("SUBMITTED");
        order.setFault(faultTag(bizNo));
        order.setBizDate(ChannelLimitService.bizDate(now));
        order.setDueAt(now + delayMs());
        order.setCreatedAt(now);
        order.setReceiptSent(0);
        order.setAttempts(0);
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            return ResponseEntity.ok(view(orderMapper.selectByBizNo(bizNo, type)));
        }
        log.info("沙箱银行受理 bizNo={} type={} channelOrderNo={} amount={} dueAt={} fault={}",
                bizNo, type, order.getChannelOrderNo(), order.getAmount(), order.getDueAt(), order.getFault());
        return ResponseEntity.ok(view(order));
    }

    @PostMapping("/query")
    public ResponseEntity<Object> query(@RequestBody String raw,
                                        @RequestHeader(value = "X-Channel-Sign", required = false) String sign,
                                        @RequestHeader(value = "X-Channel-Timestamp", required = false) String ts) {
        if (!http.verify(SandboxBankAdapter.SECRET_REF, ts, raw, sign)) {
            return ResponseEntity.status(401).body(Map.of("error", "渠道签名验签失败"));
        }
        if (faults.isDown()) {
            return ResponseEntity.status(503).body(Map.of("error", "沙箱银行宕机（故障注入 down）"));
        }
        String bizNo = read(raw).path("bizNo").asText(null);
        SandboxOrderDO order = bizNo == null ? null : orderMapper.selectAnyByBizNo(bizNo);
        if (order == null) {
            return ResponseEntity.ok(Map.of("status", "NOT_FOUND", "bizNo", String.valueOf(bizNo)));
        }
        return ResponseEntity.ok(view(order));
    }

    @PostMapping("/close")
    public ResponseEntity<Object> close(@RequestBody String raw,
                                        @RequestHeader(value = "X-Channel-Sign", required = false) String sign,
                                        @RequestHeader(value = "X-Channel-Timestamp", required = false) String ts) {
        if (!http.verify(SandboxBankAdapter.SECRET_REF, ts, raw, sign)) {
            return ResponseEntity.status(401).body(Map.of("error", "渠道签名验签失败"));
        }
        String bizNo = read(raw).path("bizNo").asText(null);
        SandboxOrderDO order = bizNo == null ? null : orderMapper.selectAnyByBizNo(bizNo);
        if (order != null) {
            orderMapper.casStatus(order.getId(), "SUBMITTED", "FAILED", System.currentTimeMillis());
        }
        return ResponseEntity.ok(Map.of("closed", order != null, "bizNo", String.valueOf(bizNo)));
    }

    /** 沙箱账单（CSV）：由渠道侧账本生成，可注入金额不符/多单/缺行差异（CH-4 比对靶）。 */
    @GetMapping(value = "/bill", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> bill(@RequestParam String bizDate,
                                       @RequestHeader(value = "X-Channel-Sign", required = false) String sign,
                                       @RequestHeader(value = "X-Channel-Timestamp", required = false) String ts) {
        if (!http.verify(SandboxBankAdapter.SECRET_REF, ts, "", sign)) {
            return ResponseEntity.status(401).body("渠道签名验签失败");
        }
        if (faults.isDown()) {
            return ResponseEntity.status(503).body("沙箱银行宕机（故障注入 down）");
        }
        List<SandboxOrderDO> orders = orderMapper.selectByBizDate(bizDate);
        ChannelFaultInjector.BillFault fault = faults.billFault(SandboxBankAdapter.CODE);
        StringBuilder sb = new StringBuilder("channel_order_no,biz_no,type,amount,status,trade_time\n");
        long count = 0;
        long sum = 0;
        for (SandboxOrderDO o : orders) {
            if ("SUBMITTED".equals(o.getStatus())) {
                continue;
            }
            if (fault != null && o.getBizNo().equals(fault.getDropBizNo())) {
                continue;
            }
            long amount = o.getAmount() == null ? 0L : o.getAmount();
            if (fault != null && o.getBizNo().equals(fault.getAmountBizNo())) {
                amount += fault.getAmountDelta();
            }
            sb.append(o.getChannelOrderNo()).append(',').append(o.getBizNo()).append(',').append(o.getType())
                    .append(',').append(amount).append(',').append(o.getStatus()).append(',')
                    .append(o.getFinalizedAt() == null ? o.getCreatedAt() : o.getFinalizedAt()).append('\n');
            count++;
            sum += Math.abs(amount);
        }
        if (fault != null) {
            for (String extra : fault.getExtraRows()) {
                sb.append(extra).append('\n');
                String[] cols = extra.split(",");
                count++;
                if (cols.length >= 4) {
                    try {
                        sum += Math.abs(Long.parseLong(cols[3].trim()));
                    } catch (NumberFormatException ignored) {
                        // 非法金额行照样写入文件：由解析器判定失败（不静默丢行）
                        sum += 0L;
                    }
                }
            }
        }
        long totalDelta = fault == null ? 0L : fault.getTotalDelta();
        sb.append("#TOTAL,").append(count).append(',').append(sum + totalDelta).append('\n');
        return ResponseEntity.ok(sb.toString());
    }

    private Map<String, Object> view(SandboxOrderDO order) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channelOrderNo", order.getChannelOrderNo());
        out.put("bizNo", order.getBizNo());
        out.put("type", order.getType());
        out.put("status", order.getStatus());
        out.put("amount", order.getAmount());
        out.put("requestedAmount", order.getRequestedAmount());
        out.put("reason", "FAILED".equals(order.getStatus()) ? "沙箱渠道拒绝（故障注入/关单）" : null);
        out.put("dueAt", order.getDueAt());
        out.put("receiptSent", order.getReceiptSent());
        return out;
    }

    private String faultTag(String bizNo) {
        if (!faults.applies(bizNo)) {
            return "NONE";
        }
        StringBuilder sb = new StringBuilder();
        if (faults.isReject()) {
            sb.append("reject,");
        }
        if (faults.isReceiptLoss()) {
            sb.append("receiptLoss,");
        }
        if (faults.isAmountMismatch()) {
            sb.append("amountMismatch,");
        }
        if (faults.getDelayMs() != null) {
            sb.append("delay=").append(faults.getDelayMs()).append(',');
        }
        return sb.length() == 0 ? "NONE" : sb.substring(0, sb.length() - 1);
    }

    private long delayMs() {
        Long override = faults.getDelayMs();
        return override != null ? override : 1500L;
    }

    private JsonNode read(String raw) {
        try {
            return json.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("报文解析失败: " + e.getMessage());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}