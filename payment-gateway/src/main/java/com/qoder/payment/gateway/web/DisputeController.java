package com.qoder.payment.gateway.web;

import com.qoder.payment.facade.dispute.DisputeCreateRequest;
import com.qoder.payment.facade.dispute.DisputeFacade;
import com.qoder.payment.facade.dispute.DisputeQuery;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * X-1 差错处理平台 REST（薄适配层，业务经 Dubbo 路由到 recon 域）。
 * 返回裸数据（与 ApiController 现有风格一致，非 Result 信封）。
 *
 * <p>S-4 默认影子模式：POST /api/disputes/autofix/run 不带 execute 时按配置
 * recon.dispute.autofix（默认 false）只返回建议策略；execute=true 显式授权执行。
 * 人工 approve 始终执行修复（人工批准即授权，幂等）。
 */
@RestController
@RequestMapping("/api/disputes")
public class DisputeController {

    @DubboReference
    private DisputeFacade disputeFacade;

    @GetMapping
    public ResponseEntity<Object> list(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) String type,
                                       @RequestParam(required = false) String source,
                                       @RequestParam(required = false) String bizNo,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        DisputeQuery query = new DisputeQuery();
        query.setStatus(status);
        query.setType(type);
        query.setSource(source);
        query.setBizNo(bizNo);
        query.setPage(page);
        query.setSize(size);
        return GatewaySupport.of(disputeFacade.list(query));
    }

    /** 人工/跨泳道登记差错单（source=PAYMENT/RESERVE 等，幂等去重）。 */
    @PostMapping
    public ResponseEntity<Object> create(@RequestBody DisputeCreateRequest request) {
        return GatewaySupport.of(disputeFacade.create(request));
    }

    @GetMapping("/stats")
    public ResponseEntity<Object> stats() {
        return GatewaySupport.of(disputeFacade.stats());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Object> get(@PathVariable long id) {
        return GatewaySupport.of(disputeFacade.get(id));
    }

    @PostMapping("/{id}/assign")
    public ResponseEntity<Object> assign(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        return GatewaySupport.of(disputeFacade.assign(id, str(body, "assignee"), operator(body)));
    }

    /** 批准执行修复（S-4 策略路由：红冲蓝补/挂账/通知重放，幂等）。 */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Object> approve(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        return GatewaySupport.of(disputeFacade.approve(id, operator(body)));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Object> reject(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        return GatewaySupport.of(disputeFacade.reject(id, operator(body), str(body, "reason")));
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<Object> resolve(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        return GatewaySupport.of(disputeFacade.resolve(id, operator(body), str(body, "resolution")));
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<Object> close(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        return GatewaySupport.of(disputeFacade.close(id, operator(body)));
    }

    /** 手动触发批量自动修复：body {execute:true|false}；缺省按配置开关（默认影子模式）。 */
    @PostMapping("/autofix/run")
    public ResponseEntity<Object> autofixRun(@RequestBody(required = false) Map<String, Object> body) {
        Boolean execute = body == null || body.get("execute") == null ? null
                : Boolean.valueOf(String.valueOf(body.get("execute")));
        return GatewaySupport.of(disputeFacade.autoFixRun(execute));
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : v.toString();
    }

    private static String operator(Map<String, Object> body) {
        String operator = str(body, "operator");
        return operator == null || operator.isBlank() ? "console" : operator;
    }
}