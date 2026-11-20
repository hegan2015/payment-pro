package com.qoder.payment.gateway.openplatform;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.openplatform.AppPolicyDTO;
import com.qoder.payment.facade.openplatform.OpenPlatformFacade;
import com.qoder.payment.facade.openplatform.SandboxAccountDTO;
import com.qoder.payment.facade.openplatform.SandboxOrderDTO;
import com.qoder.payment.facade.openplatform.SandboxTxnDTO;
import com.qoder.payment.gateway.web.GatewaySupport;
import com.qoder.payment.gateway.web.NonceStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * G-4a 沙箱路由：<b>沙箱 appId 的请求在网关就被截流</b>，只走 member 域沙箱账本，
 * 结构上不会调用 trade/payment/accounting/account 任何 facade —— 生产账零污染。
 *
 * <p>验签与生产同一口径（{@link OpenApiSignature} 与冻结 canonical 逐字节一致，timestamp 毫秒 ±300s、
 * nonce 可选去重复用 {@link NonceStore}）；响应<b>形状</b>对齐 v1 冻结面：
 * account.balance 裸数字 long、account.transfer 空体、trade.query {order,payment}、资金单/支付单含 id/status/amount/fee。
 *
 * <p>沙箱语义：fee=0、同步 SUCCESS、bizNo/outTradeNo 幂等、仅 BALANCE 支付；不产生渠道调用与真实资金变动。
 */
@Slf4j
@Component
public class SandboxRouter {

    @DubboReference
    private OpenPlatformFacade openPlatformFacade;

    @Autowired
    private NonceStore nonceStore;

    /** C-5/CH-6 沙箱受理层（收银台 9 方法 + 签约 2 方法；只调 OpenPlatformFacade 既有沙箱账本方法）。 */
    @Autowired
    private SandboxCashierSupport sandboxCashierSupport;

    /** 沙箱支持的方法目录（版本公告/契约自检单一事实来源；v1 十字 + 收银台九 + 签约二）。 */
    public static List<String> supportedMethods() {
        List<String> methods = new java.util.ArrayList<>(List.of(
                "account.open", "account.balance", "account.transfer", "account.topup", "account.withdraw",
                "fund.topup", "fund.withdraw", "trade.create", "trade.pay", "trade.query"));
        methods.addAll(SandboxCashierSupport.METHODS);
        return List.copyOf(methods);
    }

    /** 处理沙箱请求（已完成 appId 策略解析与 scope 校验）。 */
    public ResponseEntity<Object> route(String appId, String api, Map<String, Object> body, AppPolicyDTO policy) {
        try {
            OpenApiSignature.timestamp(body);
            OpenApiSignature.verifyHmac(body, policy.getSandboxSecret());
            Object nonceValue = body.get("nonce");
            if (nonceValue != null && !nonceValue.toString().isBlank()) {
                String nonce = nonceValue.toString().trim();
                if (nonce.length() > 64) {
                    throw new BizException(ResultCode.PARAM_ERROR.getCode(), "nonce 超长（最多 64 字符）");
                }
                if (!nonceStore.firstUse(appId, nonce)) {
                    throw new BizException(ResultCode.CONFLICT.getCode(), "重复请求：nonce 已使用");
                }
            }
            return dispatch(appId, api, body);
        } catch (BizException e) {
            return ResponseEntity.status(GatewaySupport.statusOf(e.getCode()))
                    .body(GatewaySupport.errorBody(e.getMessage(), e.getCode()));
        }
    }

    private ResponseEntity<Object> dispatch(String appId, String api, Map<String, Object> body) {
        switch (api) {
            case "account.open" -> {
                Result<SandboxAccountDTO> result = openPlatformFacade.sandboxOpenAccount(
                        appId, str(body, "name"), opt(body, "type", "USER"));
                if (!result.isSuccess()) {
                    return GatewaySupport.of(result);
                }
                SandboxAccountDTO account = result.getData();
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("memberId", account.getMemberId());
                data.put("accountId", account.getAccountId());
                return GatewaySupport.of(Result.ok(data));
            }
            case "account.balance" -> {
                // 红线形状：裸数字 long（与 v1 一致）
                return GatewaySupport.of(openPlatformFacade.sandboxBalance(appId, str(body, "accountId")));
            }
            case "fund.topup", "account.topup" -> {
                String kind = "TOPUP";
                Result<SandboxTxnDTO> result = openPlatformFacade.sandboxFund(appId, kind,
                        str(body, "accountId"), longOf(body.get("amount")), str(body, "bizNo"));
                return "account.topup".equals(api) ? balanceOf(appId, str(body, "accountId")) : paymentShape(result);
            }
            case "fund.withdraw", "account.withdraw" -> {
                Result<SandboxTxnDTO> result = openPlatformFacade.sandboxFund(appId, "WITHDRAW",
                        str(body, "accountId"), longOf(body.get("amount")), str(body, "bizNo"));
                return "account.withdraw".equals(api) ? ResponseEntity.ok().build() : paymentShape(result);
            }
            case "account.transfer" -> {
                Result<Boolean> result = openPlatformFacade.sandboxTransfer(appId, str(body, "fromAccountId"),
                        str(body, "toAccountId"), longOf(body.get("amount")), str(body, "bizNo"));
                if (!result.isSuccess()) {
                    return GatewaySupport.of(result);
                }
                // v1 account.transfer 返回 Result<Void> → 200 空体，形状一致
                return ResponseEntity.ok().build();
            }
            case "trade.create" -> {
                Result<SandboxOrderDTO> result = openPlatformFacade.sandboxCreateOrder(appId,
                        opt(body, "subject", "沙箱交易"), longOf(body.get("amount")), opt(body, "outTradeNo", null));
                if (!result.isSuccess()) {
                    return GatewaySupport.of(result);
                }
                return GatewaySupport.of(Result.ok(orderShape(result.getData())));
            }
            case "trade.pay" -> {
                return paymentShape(openPlatformFacade.sandboxPay(appId, str(body, "orderId"),
                        str(body, "accountId"), opt(body, "method", "BALANCE"), opt(body, "outTradeNo", null)));
            }
            case "trade.query" -> {
                Result<SandboxOrderDTO> result = openPlatformFacade.sandboxGetOrder(appId, str(body, "orderId"));
                if (!result.isSuccess()) {
                    return GatewaySupport.of(result);
                }
                SandboxOrderDTO order = result.getData();
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("order", orderShape(order));
                if (order.getPaymentId() != null) {
                    Result<SandboxTxnDTO> txn = openPlatformFacade.sandboxGetTxn(appId, order.getPaymentId());
                    if (txn.isSuccess()) {
                        data.put("payment", paymentMap(txn.getData()));
                    }
                }
                return GatewaySupport.of(Result.ok(data));
            }
            case "cashier.methods", "cashier.code.create", "cashier.code.parse", "cashier.code.query",
                 "cashier.scanPay", "cashier.verify", "cashier.payVerified", "cashier.payPassword.set",
                 "cashier.sms.send", "channel.sign", "channel.agent.pay" -> {
                // C-5 多端收银台 + CH-6 签约代扣：沙箱账本隔离（SBXA/SBXO/SBXP），生产账零污染
                return GatewaySupport.of(sandboxCashierSupport.handle(appId, api, body));
            }
            default -> throw new BizException(ResultCode.PARAM_ERROR.getCode(),
                    "沙箱环境暂不支持该方法: " + api);
        }
    }

    private ResponseEntity<Object> balanceOf(String appId, String accountId) {
        return GatewaySupport.of(openPlatformFacade.sandboxBalance(appId, accountId));
    }

    private ResponseEntity<Object> paymentShape(Result<SandboxTxnDTO> result) {
        if (!result.isSuccess()) {
            return GatewaySupport.of(result);
        }
        // v1 冻结形状：PayResultDTO {payment:{...}, repeated:bool}（ride 读 resp.payment.id）
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("payment", paymentMap(result.getData()));
        wrapper.put("repeated", Boolean.TRUE.equals(result.getData().getRepeated()));
        return GatewaySupport.of(Result.ok(wrapper));
    }

    /** 形状对齐 v1 PaymentDTO 的 ride 关键字段（id/status/amount/fee/requestId/method/bizType）。 */
    private static Map<String, Object> paymentMap(SandboxTxnDTO txn) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", txn.getId());
        map.put("orderId", txn.getOrderId());
        map.put("bizType", txn.getBizType());
        map.put("method", txn.getMethod());
        map.put("payerAccountId", txn.getPayerAccountId());
        map.put("merchantAccountId", txn.getToAccountId());
        map.put("amount", txn.getAmount());
        map.put("fee", txn.getFee());
        map.put("status", txn.getStatus());
        map.put("refundedAmount", 0L);
        map.put("requestId", txn.getRequestId());
        map.put("createdAt", txn.getCreatedAt());
        return map;
    }

    /** 形状对齐 v1 OrderDTO 的 ride 关键字段。 */
    private static Map<String, Object> orderShape(SandboxOrderDTO order) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", order.getId());
        map.put("merchantId", order.getMerchantId());
        map.put("outTradeNo", order.getOutTradeNo());
        map.put("subject", order.getSubject());
        map.put("amount", order.getAmount());
        map.put("status", order.getStatus());
        map.put("paymentId", order.getPaymentId());
        map.put("refundedAmount", 0L);
        map.put("createdAt", order.getCreatedAt());
        map.put("paidAt", order.getPaidAt());
        return map;
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new BizException("A0400", "缺少参数 " + key);
        }
        return value.toString();
    }

    private static String opt(Map<String, Object> body, String key, String defaultValue) {
        Object value = body.get(key);
        return value == null || value.toString().isBlank() ? defaultValue : value.toString();
    }

    private static Long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            throw new BizException("A0400", "amount 无效");
        }
    }
}