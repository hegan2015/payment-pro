package com.qoder.payment.gateway.web;

import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.accounting.ReserveAdjustRequest;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-7 备付金账实核对 REST（裸数据返回）。
 * check：SYS_CASH 账面 vs 模拟银行存管实际，差异→ERROR 告警+指标+留痕（差错单接线由集成期完成）。
 * adjust：模拟银行侧独立事件注入（敏感端点，需 X-Step-Up-Token），只影响实际侧不动账务。
 */
@RestController
@RequestMapping("/api/accounting/reserve")
public class ReserveController {

    @DubboReference
    private AccountingFacade accountingFacade;

    @PostMapping("/check")
    public ResponseEntity<Object> check() {
        return GatewaySupport.of(accountingFacade.reserveCheck());
    }

    @GetMapping("/logs")
    public ResponseEntity<Object> logs(@RequestParam(defaultValue = "20") int limit) {
        return GatewaySupport.of(accountingFacade.reserveLogs(limit));
    }

    @PostMapping("/adjust")
    public ResponseEntity<Object> adjust(@RequestBody ReserveAdjustRequest request) {
        return GatewaySupport.of(accountingFacade.reserveAdjust(request));
    }
}