package com.qoder.payment.gateway.web;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.facade.payment.PaymentFacade;
import com.qoder.payment.facade.trade.OrderCreateRequest;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.TradeFacade;
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * G-4b OpenAPI <b>v2 additive 端点</b>（{@code /openapi/v2/**}）。
 *
 * <p><b>兼容红线</b>：v1 的 {@link OpenApiController} <b>一个字节都没改</b>——本类是全新控制器，
 * 复用同包 {@code verifyFor}（同一套 HMAC/RSA2 canonical、timestamp 毫秒 ±300s、nonce 可选、
 * appId 热点限流），免 Bearer。v2 只增不改：
 * <ul>
 *   <li>响应统一 envelope {@code {apiVersion:"v2", data:...}}（v1 仍是裸数据）；</li>
 *   <li>trade.create 支持可选 {@code attach}（原样回显）与 {@code expireMinutes}（T-4 既有可选字段）；</li>
 *   <li>account.balance 返回 {@code {accountId,balance,currency}}（v1 仍是裸数字 long）；</li>
 *   <li>trade.query 增 {@code fee} 明细（便于对账，v1 结构不变）。</li>
 * </ul>
 * 未传 {@code X-Api-Version} 的调用方永远落在 v1 路径上，行为逐字节不变。
 */
@Slf4j
@RestController
@RequestMapping("/openapi/v2")
@Tag(name = "开放平台 v2 (G-4b)", description = "additive 版本：envelope 响应 + 可选新参数；v1 冻结不受影响")
public class OpenApiV2Controller {

    @DubboReference
    private TradeFacade tradeFacade;

    @DubboReference
    private PaymentFacade paymentFacade;

    @DubboReference
    private AccountingFacade accountingFacade;

    @Autowired
    private OpenApiController openApiController;

    @PostMapping("/trade.create")
    @Operation(summary = "v2 下单（envelope + 可选 attach/expireMinutes）")
    public ResponseEntity<Object> tradeCreate(@RequestBody Map<String, Object> body) {
        MemberDTO merchant = openApiController.verifyFor(body, "trade.create");
        OrderCreateRequest request = new OrderCreateRequest();
        request.setMerchantId(merchant.getId());
        request.setSubject(required(body, "subject"));
        request.setAmount(amount(body.get("amount")));
        request.setOutTradeNo(optional(body, "outTradeNo"));
        request.setExpireMinutes(intOpt(body.get("expireMinutes")));
        Result<OrderDTO> result = tradeFacade.createOrder(request);
        if (!result.isSuccess()) {
            return GatewaySupport.of(result);
        }
        Map<String, Object> envelope = envelope(result.getData());
        if (body.get("attach") != null) {
            envelope.put("attach", body.get("attach"));
        }
        return ResponseEntity.ok(envelope);
    }

    @PostMapping("/trade.query")
    @Operation(summary = "v2 订单+支付单查询（增 fee 明细）")
    public ResponseEntity<Object> tradeQuery(@RequestBody Map<String, Object> body) {
        openApiController.verifyFor(body, "trade.query");
        Result<OrderDTO> orderResult = tradeFacade.getOrder(required(body, "orderId"));
        if (!orderResult.isSuccess()) {
            return GatewaySupport.of(orderResult);
        }
        OrderDTO order = orderResult.getData();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order", order);
        if (order.getPaymentId() != null) {
            Result<PaymentDTO> paymentResult = paymentFacade.getPayment(order.getPaymentId());
            if (paymentResult.isSuccess()) {
                PaymentDTO payment = paymentResult.getData();
                data.put("payment", payment);
                Map<String, Object> fee = new LinkedHashMap<>();
                fee.put("amount", payment.getFee());
                fee.put("refundedFee", payment.getRefundedFee());
                fee.put("currency", "CNY");
                data.put("fee", fee);
            }
        }
        return ResponseEntity.ok(envelope(data));
    }

    @PostMapping("/account.balance")
    @Operation(summary = "v2 余额查询（对象结构；v1 仍返回裸数字 long）")
    public ResponseEntity<Object> accountBalance(@RequestBody Map<String, Object> body) {
        openApiController.verifyFor(body, "account.balance");
        String accountId = required(body, "accountId");
        Result<Long> result = accountingFacade.balance(accountId);
        if (!result.isSuccess()) {
            return GatewaySupport.of(result);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountId", accountId);
        data.put("balance", result.getData());
        data.put("currency", "CNY");
        return ResponseEntity.ok(envelope(data));
    }

    private static Map<String, Object> envelope(Object data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("apiVersion", "v2");
        envelope.put("data", data);
        return envelope;
    }

    private static String required(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new BizException("A0400", "缺少参数 " + key);
        }
        return value.toString();
    }

    private static String optional(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static Long amount(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            throw new BizException("A0400", "amount 无效");
        }
    }

    private static Integer intOpt(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception e) {
            throw new BizException("A0400", "expireMinutes 无效");
        }
    }
}