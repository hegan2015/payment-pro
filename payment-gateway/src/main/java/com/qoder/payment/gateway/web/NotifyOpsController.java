package com.qoder.payment.gateway.web;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.notify.NotifyBatchResendDTO;
import com.qoder.payment.facade.notify.NotifyFacade;
import com.qoder.payment.facade.notify.NotifyPageDTO;
import com.qoder.payment.facade.notify.NotifyQueryRequest;
import com.qoder.payment.facade.notify.NotifyRecordDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * N-4 通知查询/重发控制台 API（additive，既有 /api/notify/records|send|{id} 口径不变）。
 *
 * <p>权限点：查询/事件目录 = {@code notify:read}；重发 = {@code notify:send}（SecurityConfig additive 规则）。
 * <p><b>重发属敏感操作</b>：需 {@code X-Step-Up-Token}（StepUpAuthInterceptor additive 匹配），
 * 缺失/失效 → 403 A0403。重发幂等且<b>不重复推送已送达</b>（DELIVERED/PENDING 跳过）。
 * <p>查询结果脱敏：不含任何密钥，签名值只留预览，callbackUrl 去查询串。
 */
@Slf4j
@RestController
@RequestMapping("/api/notify")
@Tag(name = "通知查询/重发 (N-4)", description = "分页查询（脱敏）+ 单条/批量重发（step-up + 幂等）")
public class NotifyOpsController {

    @DubboReference
    private NotifyFacade notifyFacade;

    @GetMapping("/query")
    @Operation(summary = "通知记录分页查询（脱敏）",
            description = "按商户 appId / 事件 / 状态 / 业务单号 / 创建时间窗 AND 组合分页。"
                    + "page 从 1 起，size 默认 20 上限 200。返回 records 已脱敏（无密钥、签名仅预览）。")
    public ResponseEntity<Object> query(
            @Parameter(description = "商户 appId") @RequestParam(required = false) String appId,
            @Parameter(description = "事件类型，如 PAY_SUCCESS/SHARE_SUCCESS") @RequestParam(required = false) String event,
            @Parameter(description = "PENDING/DELIVERED/FAILED/PULL") @RequestParam(required = false) String status,
            @RequestParam(required = false) String bizId,
            @Parameter(description = "created_at 起（毫秒，含）") @RequestParam(required = false) Long from,
            @Parameter(description = "created_at 止（毫秒，含）") @RequestParam(required = false) Long to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        NotifyQueryRequest request = new NotifyQueryRequest();
        request.setAppId(appId);
        request.setEvent(event);
        request.setStatus(status);
        request.setBizId(bizId);
        request.setFrom(from);
        request.setTo(to);
        request.setPage(page);
        request.setSize(size);
        Result<NotifyPageDTO> result = notifyFacade.query(request);
        return GatewaySupport.of(result);
    }

    @GetMapping("/events")
    @Operation(summary = "通知事件类型目录 (N-3)",
            description = "返回事件名/说明/是否冻结(legacy)/是否 envelope/_payload 必填字段。"
                    + "PAY_SUCCESS 与 REFUND_SUCCESS 为冻结事件，body 逐字节不变。")
    public ResponseEntity<Object> events() {
        return GatewaySupport.of(notifyFacade.events());
    }

    @PostMapping("/{id}/resend")
    @Operation(summary = "单条通知重发（敏感操作，需 X-Step-Up-Token）",
            description = "仅 FAILED/PULL 重置 PENDING 重推；DELIVERED/PENDING 跳过（不重复推送）。"
                    + "幂等键 (NOTIFY_RESEND, notifyId:requestId)：同 requestId 重复调用返回首次结果。"
                    + "不产生新记录，resendCount 累加。")
    public ResponseEntity<Object> resend(@PathVariable String id,
                                         @RequestBody(required = false) Map<String, Object> body) {
        String requestId = body == null || body.get("requestId") == null ? null : body.get("requestId").toString();
        Result<NotifyRecordDTO> result = notifyFacade.resend(id, requestId);
        log.info("控制台通知重发 notifyId={} requestId={} operator={}", id, requestId,
                com.qoder.payment.gateway.security.AuditSupport.currentUsername());
        return GatewaySupport.of(result);
    }

    @PostMapping("/resend/batch")
    @Operation(summary = "批量通知重发（敏感操作，需 X-Step-Up-Token）",
            description = "body: {notifyIds:[...], requestId}。requestId 必填（幂等键 NOTIFY_RESEND_BATCH），"
                    + "单批上限 100 条；逐条 CAS 重置，已送达/在途跳过。")
    public ResponseEntity<Object> resendBatch(@RequestBody Map<String, Object> body) {
        Object ids = body == null ? null : body.get("notifyIds");
        if (!(ids instanceof List<?> list)) {
            return ResponseEntity.status(400)
                    .body(GatewaySupport.errorBody("notifyIds 必填且为数组", "A0400"));
        }
        String requestId = body.get("requestId") == null ? null : body.get("requestId").toString();
        List<String> notifyIds = list.stream().map(String::valueOf).toList();
        Result<NotifyBatchResendDTO> result = notifyFacade.resendBatch(notifyIds, requestId);
        log.info("控制台通知批量重发 requestId={} count={} operator={}", requestId, notifyIds.size(),
                com.qoder.payment.gateway.security.AuditSupport.currentUsername());
        return GatewaySupport.of(result);
    }
}