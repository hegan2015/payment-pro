package com.qoder.payment.channel.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.payment.channel.service.ChannelReceiptService;
import com.qoder.payment.channel.web.ChannelOpsSupport;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.facade.channel.ChannelReceiptRequest;
import com.qoder.payment.facade.channel.ChannelResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CH-2b 沙箱回执回调接收（平台侧，端口 18087 内部面）：<b>先验签，再走 CH-1 既有回执路径</b>。
 *
 * <p>验签：HMAC-SHA256（密钥经 S-1 SecretProvider，ref={@value SandboxBankAdapter#SECRET_REF}），
 * 签名口径与平台 OpenAPI 同构（TreeMap 排序 k=v&，timestamp 毫秒）；验签失败 401，绝不落地。
 * 落地：{@link ChannelReceiptService}（回执日志 UNIQUE 去重 + channel_trade 状态 CAS + 金额一致性校验），
 * 因此重复/乱序/金额不符回执一律被既有护栏吸收，本控制器不新增任何状态改写逻辑。
 *
 * <p>与网关 {@code POST /api/channel/receipt}（人工/跨域补录，Bearer+RBAC）并存：
 * 本端点是「渠道方推送」入口，只在内网面暴露，不经网关。
 */
@Slf4j
@RestController
@RequestMapping("/channel-ops/sandbox")
@RequiredArgsConstructor
public class SandboxReceiptController {

    private final ChannelHttpClient http;
    private final SandboxBankAdapter adapter;
    private final ChannelReceiptService receiptService;
    private final ObjectMapper json = new ObjectMapper();

    @PostMapping("/receipt")
    public ResponseEntity<Object> receipt(@RequestBody String raw,
                                          @RequestHeader(value = "X-Channel-Sign", required = false) String sign,
                                          @RequestHeader(value = "X-Channel-Timestamp", required = false) String ts) {
        if (!http.verify(SandboxBankAdapter.SECRET_REF, ts, raw, sign)) {
            log.warn("沙箱回执验签失败（拒绝落地）timestamp={}", ts);
            return ResponseEntity.status(401).body(java.util.Map.of("error", "渠道回执验签失败", "code", "A0401"));
        }
        try {
            JsonNode node = json.readTree(raw);
            ChannelResult parsed = adapter.parseReceipt(raw);
            ChannelReceiptRequest request = new ChannelReceiptRequest();
            request.setBizNo(node.path("bizNo").asText(null));
            request.setChannelOrderNo(parsed.getChannelOrderNo());
            request.setStatus(parsed.getChannelStatus());
            request.setAmount(parsed.getAmount());
            request.setReason(parsed.getReason());
            request.setRaw(raw);
            return ResponseEntity.ok(receiptService.receiveReceipt(request));
        } catch (BizException e) {
            log.warn("沙箱回执被既有护栏拒绝: {}", e.getMessage());
            return ChannelOpsSupport.error(e);
        } catch (Exception e) {
            return ChannelOpsSupport.badRequest("回执报文非法: " + e.getMessage());
        }
    }
}