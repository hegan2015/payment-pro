package com.qoder.payment.channel.web;

import com.qoder.payment.common.exception.BizException;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** 渠道域内部 HTTP 面（18087）错误码 → 状态码适配（与网关 GatewaySupport 口径一致）。 */
public final class ChannelOpsSupport {

    private ChannelOpsSupport() {
    }

    public static ResponseEntity<Object> error(BizException e) {
        int status = switch (e.getCode() == null ? "" : e.getCode()) {
            case "A0404" -> 404;
            case "A0401" -> 401;
            case "A0403" -> 403;
            case "A0409" -> 409;
            case "A0429" -> 429;
            case "B0003" -> 503;
            case "C0001" -> 502;
            case "B0002" -> 500;
            default -> 400;
        };
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage(), "code", String.valueOf(e.getCode())));
    }

    public static ResponseEntity<Object> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message, "code", "A0400"));
    }
}