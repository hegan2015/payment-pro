package com.qoder.payment.gateway.web;

import com.qoder.payment.gateway.gray.GrayRouter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * I-9 灰度发布管理面（ADMIN 专属，SecurityConfig additive 规则 /api/gray/** → console:admin）。
 *
 * <p>状态外置共享 Redis（docker），任一实例发布/回滚，全实例下一个请求即生效；
 * local 无 Redis 时本进程内存态（零依赖，单实例语义）。
 *
 * <p>{@code POST /api/gray/rollback} 为<b>一键回滚</b>：enabled=false + weight=0，
 * 全部流量立即回稳定实例（target 保留便于审计与再次发布）。
 */
@Slf4j
@RestController
@RequestMapping("/api/gray")
@RequiredArgsConstructor
@Tag(name = "灰度发布 (I-9)", description = "header/权重分流 + 共享状态 + 一键回滚")
public class GrayController {

    private final GrayRouter router;

    @GetMapping("/status")
    @Operation(summary = "灰度状态与本实例标识", description = "返回 instanceId/enabled/weight/tag/target/stateSource 与分流规则说明。")
    public ResponseEntity<Object> status() {
        return ResponseEntity.ok(view(router.state()));
    }

    @PostMapping("/config")
    @Operation(summary = "发布/调整灰度", description = "body: {enabled, weight(0-100), tag, target}。"
            + "target 为灰度实例基址（如 http://127.0.0.1:8081）。写共享 Redis，全实例即时生效。")
    public ResponseEntity<Object> config(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        boolean enabled = Boolean.parseBoolean(String.valueOf(payload.getOrDefault("enabled", "false")));
        Integer weight = payload.get("weight") instanceof Number n ? n.intValue() : null;
        String tag = payload.get("tag") == null ? null : payload.get("tag").toString();
        String target = payload.get("target") == null ? null : payload.get("target").toString();
        GrayRouter.GrayState state = router.publish(enabled, weight, tag, target);
        log.info("灰度发布操作 operator={} enabled={} weight={} target={}",
                com.qoder.payment.gateway.security.AuditSupport.currentUsername(),
                state.enabled(), state.weight(), state.target());
        return ResponseEntity.ok(view(state));
    }

    @PostMapping("/rollback")
    @Operation(summary = "一键回滚灰度", description = "enabled=false + weight=0，全部流量回稳定实例；无需其它参数。")
    public ResponseEntity<Object> rollback() {
        GrayRouter.GrayState state = router.rollback();
        log.info("灰度一键回滚 operator={} → enabled=false weight=0",
                com.qoder.payment.gateway.security.AuditSupport.currentUsername());
        return ResponseEntity.ok(view(state));
    }

    private Map<String, Object> view(GrayRouter.GrayState state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", router.instanceId());
        result.put("enabled", state.enabled());
        result.put("weight", state.weight());
        result.put("tag", state.tag());
        result.put("target", state.target());
        result.put("stateSource", router.stateSource());
        result.put("rules", "X-Gray-Tag: gray 强制灰度 / stable 强制稳定；否则 crc32(Bearer token 或客户端 IP)%100 < weight → 灰度");
        result.put("headers", "响应 X-Instance-Id=受理实例，X-Gray-Decision=gray|stable|stable-failopen，X-Gray-Upstream-Instance=灰度实例标识");
        return result;
    }
}