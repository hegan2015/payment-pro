package com.qoder.payment.gateway.web;

import com.qoder.payment.facade.cashier.CashierFacade;
import com.qoder.payment.facade.cashier.CreateCodeRequest;
import com.qoder.payment.facade.cashier.PayCodeFacade;
import com.qoder.payment.facade.cashier.ScanPayRequest;
import com.qoder.payment.facade.cashier.SetPayPasswordRequest;
import com.qoder.payment.facade.cashier.VerifiedPayRequest;
import com.qoder.payment.facade.cashier.VerifyRequest;
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
 * P1 F 泳道收银台工作流新端点（C-1/C-2/C-3，<b>全部 additive</b>）。
 *
 * <p><b>ride 红线</b>：不改既有 {@code GET /api/cashier} 与 {@code POST /api/cashier/pay}（ApiController），
 * BALANCE 同步受理语义逐字节不变；本控制器只新增验证前置/付款码/扫码/账户级渲染端点。
 * 安全：落在 SecurityConfig 兜底 {@code /api/** authenticated}（无需新权限点，登录态即可调用）；
 * 静态 C 端 H5（/h5/**）由 anyRequest permitAll 提供，页面内 fetch 带 Bearer。
 */
@RestController
@RequestMapping("/api/cashier")
public class CashierOpsController {

    @DubboReference
    private CashierFacade cashierFacade;

    @DubboReference
    private PayCodeFacade payCodeFacade;

    /** C-1 账户级多支付方式渲染（accountId 可选；缺省回退聚合渲染，与 GET /api/cashier 同构）。 */
    @GetMapping("/methods")
    public ResponseEntity<Object> methods(@RequestParam String orderId,
                                          @RequestParam(required = false) String accountId) {
        if (accountId != null && !accountId.isBlank()) {
            return GatewaySupport.of(cashierFacade.cashierForPayer(orderId, accountId));
        }
        return GatewaySupport.of(cashierFacade.cashier(orderId));
    }

    /** C-2 生成付款码（被扫）/收款码（主扫），沙箱。 */
    @PostMapping("/code")
    public ResponseEntity<Object> createCode(@RequestBody CreateCodeRequest request) {
        return GatewaySupport.of(payCodeFacade.createCode(request));
    }

    /** C-2 解析码（只读，反映状态/有效期）。 */
    @PostMapping("/code/parse")
    public ResponseEntity<Object> parseCode(@RequestBody Map<String, Object> body) {
        return GatewaySupport.of(payCodeFacade.parseCode(str(body.get("code"))));
    }

    /** C-2 查询码状态（H5 付款码页轮询：ACTIVE→CONSUMED/EXPIRED）。 */
    @GetMapping("/code/{code}")
    public ResponseEntity<Object> getCode(@PathVariable String code) {
        return GatewaySupport.of(payCodeFacade.parseCode(code));
    }

    /** C-2 扫码受理：一次性核销 + 验证前置 + 幂等支付（BALANCE 同步语义不变）。 */
    @PostMapping("/scan-pay")
    public ResponseEntity<Object> scanPay(@RequestBody ScanPayRequest request) {
        request.setMethod(ApiController.normalizeMethod(request.getMethod()));
        return GatewaySupport.of(payCodeFacade.scanPay(request));
    }

    /** C-3 支付验证（口令/短信沙箱/指纹模拟/小额免密），通过签发一次性 verifyToken。 */
    @PostMapping("/verify")
    public ResponseEntity<Object> verify(@RequestBody VerifyRequest request) {
        return GatewaySupport.of(cashierFacade.verify(request));
    }

    /** C-3 验证前置受理（H5 收银台）：验证失败不受理；同 requestId 不重复扣款。 */
    @PostMapping("/pay-verified")
    public ResponseEntity<Object> payVerified(@RequestBody VerifiedPayRequest request) {
        request.setMethod(ApiController.normalizeMethod(request.getMethod()));
        return GatewaySupport.of(cashierFacade.payVerified(request));
    }

    /** C-3 设置/重置支付密码（沙箱）。 */
    @PostMapping("/pay-password")
    public ResponseEntity<Object> setPayPassword(@RequestBody SetPayPasswordRequest request) {
        return GatewaySupport.of(cashierFacade.setPayPassword(request));
    }

    /** C-3 发送短信验证码（沙箱，码值仅沙箱返回）。 */
    @PostMapping("/sms/send")
    public ResponseEntity<Object> sendSms(@RequestBody Map<String, Object> body) {
        return GatewaySupport.of(cashierFacade.sendSms(str(body.get("accountId")),
                str(body.get("memberId")), str(body.get("orderId"))));
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}