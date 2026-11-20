package com.qoder.payment.cashier.service;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.cashier.mapper.PayCodeMapper;
import com.qoder.payment.cashier.model.PayCodeDO;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.cashier.CreateCodeRequest;
import com.qoder.payment.facade.cashier.PayCodeDTO;
import com.qoder.payment.facade.cashier.ScanPayRequest;
import com.qoder.payment.facade.payment.PayRequest;
import com.qoder.payment.facade.payment.PayResultDTO;
import com.qoder.payment.facade.payment.PaymentFacade;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.TradeFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * C-2 付款码/扫码（沙箱）：被扫（用户出示 18 位数字码）+ 主扫（商户出二维码令牌）。
 * 码生成/解析/有效期/一次性核销（CAS）/防重放/核销幂等（requestId）/核销与支付联动幂等。
 *
 * <p><b>沙箱边界</b>：不接真实微信/支付宝；码值为 SecureRandom 随机数/令牌，<b>不嵌入账户口令等敏感信息</b>；
 * 核销后以同一 requestId 委托支付核心，BALANCE 走同步语义（与 trade.pay 逐字节一致，不引入 WAITING/异步回执）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PayCodeMapper mapper;
    private final DynamicConfig config;
    private final PayVerifyService verifyService;

    @DubboReference
    private TradeFacade tradeFacade;
    @DubboReference
    private AccountFacade accountFacade;
    @DubboReference
    private PaymentFacade paymentFacade;

    public PayCodeDTO createCode(CreateCodeRequest req) {
        String type = req.getType() == null ? "USER_PRESENTED" : req.getType().toUpperCase();
        if (!type.equals("USER_PRESENTED") && !type.equals("MERCHANT_PRESENTED")) {
            throw new BizException(ResultCode.PARAM_ERROR, "type 须为 USER_PRESENTED/MERCHANT_PRESENTED");
        }
        // 生成幂等：同 requestId+type 已有有效码直接返回
        if (req.getRequestId() != null && !req.getRequestId().isBlank()) {
            PayCodeDO prior = mapper.selectByRequest(req.getRequestId(), type);
            if (prior != null && "ACTIVE".equals(prior.getStatus()) && prior.getExpireAt() > System.currentTimeMillis()) {
                return toDTO(prior);
            }
        }
        OrderDTO order = null;
        long amount;
        String merchantName = null;
        String subject = null;
        String orderId = req.getOrderId();
        if (orderId != null && !orderId.isBlank()) {
            order = tradeFacade.getOrder(orderId).orThrow();
            amount = order.getAmount();
            merchantName = order.getMerchantName();
            subject = order.getSubject();
        } else {
            if (req.getAmount() == null || req.getAmount() <= 0) {
                throw new BizException(ResultCode.PARAM_ERROR, "orderId/amount 至少一项必填且金额>0");
            }
            amount = req.getAmount();
        }
        String accountId = req.getAccountId();
        if (type.equals("USER_PRESENTED")) {
            if (accountId == null || accountId.isBlank()) {
                throw new BizException(ResultCode.PARAM_ERROR, "被扫码须指定付款账户 accountId");
            }
        } else if (accountId == null && order != null) {
            AccountDTO ma = accountFacade.getByMember(order.getMerchantId()).orThrow();
            accountId = ma.getId();
        }

        long now = System.currentTimeMillis();
        long ttl = config.getLong("cashier.code.expire-seconds", 60L) * 1000L;
        PayCodeDO code = new PayCodeDO();
        code.setType(type);
        code.setAccountId(accountId);
        code.setOrderId(orderId);
        code.setAmount(amount);
        code.setStatus("ACTIVE");
        code.setExpireAt(now + ttl);
        code.setRequestId(req.getRequestId());
        code.setMerchantName(merchantName);
        code.setSubject(subject);
        code.setCreatedAt(now);
        if (type.equals("USER_PRESENTED")) {
            String digits = uniqueNumericCode();
            code.setCode(digits);
            code.setQrContent(digits);
        } else {
            String token = "MP" + UUID.randomUUID().toString().replace("-", "");
            code.setCode(token);
            // 沙箱收款码内容（example 域，明确非真实渠道）；前端据此绘制 QR，用户扫码后解析 token
            code.setQrContent("https://cashier.payment-pro.example/scan?code=" + token + "&amount=" + amount);
        }
        mapper.insert(code);
        log.info("C-2 生成{}码 orderId={} amount={} expireAt={}", type, orderId, amount, code.getExpireAt());
        return toDTO(code);
    }

    /** 解析码（只读，反映状态；过期惰性标记）。受理拒绝在 scanPay 强制。 */
    public PayCodeDTO parseCode(String codeValue) {
        PayCodeDO code = mapper.selectByCode(codeValue);
        if (code == null) {
            throw new BizException(ResultCode.NOT_FOUND, "付款码不存在");
        }
        long now = System.currentTimeMillis();
        if ("ACTIVE".equals(code.getStatus()) && code.getExpireAt() <= now) {
            mapper.markExpired(codeValue, now);
            code.setStatus("EXPIRED");
        }
        return toDTO(code);
    }

    /** 扫码受理：幂等重入判定 → 验证前置（C-3）→ 一次性核销 CAS（防重放）→ 幂等支付。 */
    public PayResultDTO scanPay(ScanPayRequest req) {
        if (req.getRequestId() == null || req.getRequestId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "requestId 必填（幂等键）");
        }
        if (req.getCode() == null || req.getCode().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "code 必填");
        }
        PayCodeDO code = mapper.selectByCode(req.getCode());
        if (code == null) {
            throw new BizException(ResultCode.NOT_FOUND, "付款码不存在");
        }
        long now = System.currentTimeMillis();
        if ("ACTIVE".equals(code.getStatus()) && code.getExpireAt() <= now) {
            mapper.markExpired(req.getCode(), now);
            code.setStatus("EXPIRED");
        }
        if ("EXPIRED".equals(code.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "付款码已过期，请刷新");
        }
        if (code.getOrderId() == null || code.getOrderId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "码未绑定订单，无法受理");
        }
        String orderId = code.getOrderId();
        long amount = code.getAmount();
        String payerAccountId = "USER_PRESENTED".equals(code.getType())
                ? code.getAccountId() : req.getPayerAccountId();
        if (payerAccountId == null || payerAccountId.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "主扫须指定付款账户 payerAccountId");
        }
        String method = normalizeMethod(req.getMethod());

        // 幂等重入：已被本 requestId 核销 → 跳过验证（令牌已一次性核销），直接幂等支付
        if ("CONSUMED".equals(code.getStatus())) {
            if (req.getRequestId().equals(code.getConsumeRequestId())) {
                return doPay(orderId, method, payerAccountId, req);
            }
            throw new BizException(ResultCode.CONFLICT, "付款码已被其他请求核销（防重放）");
        }

        // 验证前置（C-3）：失败不得受理，且不核销码（可重试）
        verifyGate(req.getVerifyToken(), req.getPayPassword(), orderId, amount, payerAccountId);

        // 一次性核销 CAS：ACTIVE→CONSUMED 只一次
        int updated = mapper.casConsume(req.getCode(), req.getRequestId(), System.currentTimeMillis());
        if (updated == 0) {
            PayCodeDO again = mapper.selectByCode(req.getCode());
            if (again != null && "CONSUMED".equals(again.getStatus())
                    && req.getRequestId().equals(again.getConsumeRequestId())) {
                return doPay(orderId, method, payerAccountId, req);
            }
            throw new BizException(ResultCode.CONFLICT, "付款码已被核销（防重放）");
        }
        return doPay(orderId, method, payerAccountId, req);
    }

    private PayResultDTO doPay(String orderId, String method, String payerAccountId, ScanPayRequest req) {
        PayRequest pay = new PayRequest();
        pay.setOrderId(orderId);
        pay.setMethod(method);
        pay.setAccountId(payerAccountId);
        pay.setCardNo(req.getCardNo());
        pay.setRequestId(req.getRequestId());
        pay.setCallbackUrl(req.getCallbackUrl());
        // 与 CashierService.pay 同一委托：BALANCE 同步受理→PROCESSING→SUCCESS，语义逐字节不变
        return paymentFacade.pay(pay).orThrow();
    }

    private void verifyGate(String verifyToken, String payPassword, String orderId, long amount, String payerAccountId) {
        if (verifyToken != null && !verifyToken.isBlank()) {
            if (!verifyService.consumeVerifyToken(verifyToken, orderId, amount, payerAccountId)) {
                throw new BizException(ResultCode.FORBIDDEN, "支付验证无效或已过期，请重新验证");
            }
            return;
        }
        if (payPassword != null && !payPassword.isBlank()) {
            verifyService.verifyPasswordInline(payerAccountId, null, orderId, amount, payPassword);
            return;
        }
        if (amount > verifyService.freeLimit()) {
            throw new BizException(ResultCode.FORBIDDEN, "超出小额免密限额，需支付验证");
        }
    }

    private static String normalizeMethod(String method) {
        if (method == null || method.isBlank()) {
            return "BALANCE";
        }
        return "BANKCARD".equals(method) ? "CARD" : method;
    }

    /** 18 位数字码：17 位随机 + Luhn 校验位；碰撞重试。不含账户/金额等敏感信息。 */
    private String uniqueNumericCode() {
        for (int attempt = 0; attempt < 8; attempt++) {
            StringBuilder sb = new StringBuilder(18);
            for (int i = 0; i < 17; i++) {
                sb.append(RANDOM.nextInt(10));
            }
            sb.append(luhnCheckDigit(sb.toString()));
            String candidate = sb.toString();
            if (mapper.selectByCode(candidate) == null) {
                return candidate;
            }
        }
        throw new BizException(ResultCode.SYSTEM_ERROR, "付款码生成碰撞，请重试");
    }

    private static int luhnCheckDigit(String digits) {
        int sum = 0;
        boolean doubleIt = true; // 校验位为最右，故从右往左第一位（17 位末位）加倍
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return (10 - (sum % 10)) % 10;
    }

    private PayCodeDTO toDTO(PayCodeDO code) {
        PayCodeDTO dto = new PayCodeDTO();
        dto.setCode(code.getCode());
        dto.setType(code.getType());
        dto.setAccountId(code.getAccountId());
        dto.setOrderId(code.getOrderId());
        dto.setAmount(code.getAmount());
        dto.setStatus(code.getStatus());
        dto.setExpireAt(code.getExpireAt());
        dto.setCreatedAt(code.getCreatedAt());
        dto.setConsumedAt(code.getConsumedAt());
        dto.setQrContent(code.getQrContent());
        dto.setMerchantName(code.getMerchantName());
        dto.setSubject(code.getSubject());
        if ("USER_PRESENTED".equals(code.getType()) && code.getCode() != null && code.getCode().length() == 18) {
            dto.setDisplayCode(code.getCode().replaceAll("(.{4})(?=.)", "$1 "));
        } else {
            dto.setDisplayCode(code.getCode());
        }
        long ttl = (code.getExpireAt() - System.currentTimeMillis()) / 1000L;
        dto.setTtlSeconds(Math.max(0, ttl));
        return dto;
    }
}