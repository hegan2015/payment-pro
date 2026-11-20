package com.qoder.payment.gateway.web;

import com.qoder.payment.facade.channel.ChannelBillDTO;
import com.qoder.payment.facade.channel.ChannelFacade;
import com.qoder.payment.facade.channel.ChannelRouteRequest;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * CH-2a/CH-3/CH-4/CH-5 渠道运维控制台 REST（<b>新增独立控制器，不改网关既有文件</b>）。
 *
 * <p>薄适配层：业务经 Dubbo 路由到 channel 域，返回裸数据（与 ApiController 风格一致）。
 * 鉴权：读端点由安全链 {@code GET /api/channel/** → channel:read} 覆盖；
 * 写端点（限额/维护/账单下载与比对）在本控制器内校验权限点 {@code channel:write}
 * 或 ADMIN 通配 {@code *}（与 ConfigController 同模式，不改 SecurityConfig），越权 403 A0403。
 *
 * <p>渠道对账文件下载/比对会登记差错单（source=PAYMENT，策略 MANUAL，不自动动账），
 * 故按写操作鉴权；沙箱故障注入与 SPI 直探只在 channel 域内网面（18087）提供，不经网关。
 */
@RestController
@RequestMapping("/api/channel")
public class ChannelOpsController {

    public static final String PERMISSION_WRITE = "channel:write";

    @DubboReference
    private ChannelFacade channelFacade;

    // ---------- 读（channel:read） ----------

    @GetMapping("/configs")
    public ResponseEntity<Object> configs() {
        return GatewaySupport.of(channelFacade.listChannels());
    }

    @GetMapping("/adapters")
    public ResponseEntity<Object> adapters() {
        return GatewaySupport.of(channelFacade.listAdapters());
    }

    @GetMapping("/metrics")
    public ResponseEntity<Object> metrics() {
        return GatewaySupport.of(channelFacade.channelMetrics());
    }

    /** 路由决策预览（与受理同一口径）：GET /api/channel/route?amount=2000&channelCode=&cardNo= */
    @GetMapping("/route")
    public ResponseEntity<Object> route(@RequestParam(required = false) Long amount,
                                        @RequestParam(required = false) String channelCode,
                                        @RequestParam(required = false) String cardNo,
                                        @RequestParam(required = false) String cardBin,
                                        @RequestParam(required = false) String type) {
        ChannelRouteRequest request = new ChannelRouteRequest();
        request.setAmount(amount);
        request.setChannelCode(channelCode);
        request.setCardNo(cardNo);
        request.setCardBin(cardBin);
        request.setType(type);
        return GatewaySupport.of(channelFacade.route(request));
    }

    @GetMapping("/{code}/limit")
    public ResponseEntity<Object> limit(@PathVariable String code) {
        return GatewaySupport.of(channelFacade.getChannelLimit(code));
    }

    /** 渠道侧查询（SPI 只读视图，与本地 CAS 快照 /api/channel/trades/{bizNo} 区分）。 */
    @GetMapping("/trades/{bizNo}/side")
    public ResponseEntity<Object> tradeSide(@PathVariable String bizNo) {
        return GatewaySupport.of(channelFacade.queryChannelSide(bizNo));
    }

    @GetMapping("/bills")
    public ResponseEntity<Object> bills(@RequestParam(required = false) String channelCode,
                                        @RequestParam(required = false) String bizDate) {
        return GatewaySupport.of(channelFacade.listBills(channelCode, bizDate));
    }

    @GetMapping("/bills/{id}")
    public ResponseEntity<Object> bill(@PathVariable long id) {
        return GatewaySupport.of(channelFacade.getBillDetail(id));
    }

    // ---------- 写（channel:write / ADMIN *） ----------

    @PostMapping("/{code}/limit")
    public ResponseEntity<Object> setLimit(@PathVariable String code, @RequestBody(required = false) Map<String, Object> body) {
        ResponseEntity<Object> denied = denyIfNotWriter();
        if (denied != null) {
            return denied;
        }
        return GatewaySupport.of(channelFacade.setChannelLimit(code, lng(body, "singleLimit"), lng(body, "dailyLimit")));
    }

    @PostMapping("/{code}/maintenance")
    public ResponseEntity<Object> setMaintenance(@PathVariable String code, @RequestBody(required = false) Map<String, Object> body) {
        ResponseEntity<Object> denied = denyIfNotWriter();
        if (denied != null) {
            return denied;
        }
        return GatewaySupport.of(channelFacade.setMaintenance(code, lng(body, "start"), lng(body, "end")));
    }

    @PostMapping("/bills/download")
    public ResponseEntity<Object> downloadBill(@RequestBody Map<String, Object> body) {
        ResponseEntity<Object> denied = denyIfNotWriter();
        if (denied != null) {
            return denied;
        }
        String channelCode = body.get("channelCode") == null ? null : String.valueOf(body.get("channelCode"));
        String bizDate = body.get("bizDate") == null ? null : String.valueOf(body.get("bizDate"));
        return GatewaySupport.of(channelFacade.downloadBill(channelCode, bizDate));
    }

    @PostMapping("/bills/{id}/reconcile")
    public ResponseEntity<Object> reconcile(@PathVariable long id) {
        ResponseEntity<Object> denied = denyIfNotWriter();
        if (denied != null) {
            return denied;
        }
        return GatewaySupport.of(channelFacade.reconcileBill(id));
    }

    // ---------- 内部 ----------

    private static ResponseEntity<Object> denyIfNotWriter() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期", "code", "A0401"));
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String value = authority.getAuthority();
            if (PERMISSION_WRITE.equals(value) || "*".equals(value)) {
                return null;
            }
        }
        return ResponseEntity.status(403).body(Map.of("error", "无权限执行该操作", "code", "A0403"));
    }

    private static Long lng(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}