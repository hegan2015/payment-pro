package com.qoder.payment.gateway.web;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.notify.NotifyFacade;
import com.qoder.payment.facade.notify.NotifyPageDTO;
import com.qoder.payment.facade.notify.NotifyQueryRequest;
import com.qoder.payment.facade.notify.NotifyRecordDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * N-4 商户侧通知补查/重发 OpenAPI（additive 新方法，八个冻结方法一字不改）。
 *
 * <p>验签复用 {@link OpenApiController} 的既有 HMAC/RSA2 链（canonical 逐字节不变、
 * timestamp 毫秒 ±300s、nonce 可选、appId 热点限流受理前拒绝），<b>免 Bearer</b>（§0.7 红线）。
 *
 * <p><b>商户隔离</b>：notify.query 强制 appId=验签商户自身（不接受 body 传他人 appId）；
 * notify.resend 校验记录归属，非本商户记录 → 403 A0403。
 * 商户侧重发以 HMAC + 归属校验为闸门（step-up 只作用于控制台 /api/**，与既有闸门作用域一致）。
 */
@Slf4j
@RestController
@RequestMapping("/openapi")
@Tag(name = "商户通知补查/重发 (N-4)", description = "HMAC 验签，免 Bearer；商户只能操作自己的通知记录")
public class OpenApiNotifyController {

    @DubboReference
    private NotifyFacade notifyFacade;

    @Autowired
    private OpenApiController openApiController;

    @PostMapping("/notify.query")
    @Operation(summary = "notify.query 通知记录分页补查（脱敏）",
            description = "入参：appId/sign/timestamp(+可选 nonce/sign_type) + 可选 event/status/bizId/from/to/page/size。"
                    + "appId 过滤强制为验签商户自身。返回 {total,page,size,records}，records 已脱敏。")
    public ResponseEntity<Object> query(@RequestBody Map<String, Object> body) {
        MemberDTO merchant = openApiController.verifyFor(body, "notify.query");
        NotifyQueryRequest request = new NotifyQueryRequest();
        request.setAppId(merchant.getAppId());
        request.setEvent(opt(body, "event"));
        request.setStatus(opt(body, "status"));
        request.setBizId(opt(body, "bizId"));
        request.setFrom(longOpt(body, "from"));
        request.setTo(longOpt(body, "to"));
        request.setPage(intOpt(body, "page"));
        request.setSize(intOpt(body, "size"));
        Result<NotifyPageDTO> result = notifyFacade.query(request);
        return GatewaySupport.of(result);
    }

    @PostMapping("/notify.resend")
    @Operation(summary = "notify.resend 通知重发（幂等，不重复推送已送达）",
            description = "入参：appId/sign/timestamp + notifyId + requestId(幂等键)。"
                    + "仅 FAILED/PULL 重推；DELIVERED/PENDING 跳过。记录非本商户 → 403 A0403。")
    public ResponseEntity<Object> resend(@RequestBody Map<String, Object> body) {
        MemberDTO merchant = openApiController.verifyFor(body, "notify.resend");
        String notifyId = required(body, "notifyId");
        String requestId = required(body, "requestId");
        NotifyRecordDTO record = notifyFacade.get(notifyId).orThrow();
        if (record.getAppId() == null || !record.getAppId().equals(merchant.getAppId())) {
            log.warn("OpenAPI 通知重发越权拒绝 appId={} notifyId={} recordAppId={}",
                    merchant.getAppId(), notifyId, record.getAppId());
            throw new BizException("A0403", "通知记录不属于该商户");
        }
        return GatewaySupport.of(notifyFacade.resend(notifyId, requestId));
    }

    private static String required(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new BizException("A0400", "缺少参数 " + key);
        }
        return value.toString();
    }

    private static String opt(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static Long longOpt(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (Exception e) {
            throw new BizException("A0400", key + " 无效");
        }
    }

    private static Integer intOpt(Map<String, Object> body, String key) {
        Long value = longOpt(body, key);
        return value == null ? null : value.intValue();
    }
}