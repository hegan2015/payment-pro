package com.qoder.payment.cashier.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.cashier.CashierDTO;
import com.qoder.payment.facade.cashier.PayMethodDTO;
import com.qoder.payment.facade.cashier.SetPayPasswordRequest;
import com.qoder.payment.facade.cashier.VerifiedPayRequest;
import com.qoder.payment.facade.cashier.VerifyRequest;
import com.qoder.payment.facade.cashier.VerifyResultDTO;
import com.qoder.payment.facade.payment.PayRequest;
import com.qoder.payment.facade.payment.PayResultDTO;
import com.qoder.payment.facade.payment.PaymentFacade;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.TradeFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CashierService {

    @DubboReference
    private TradeFacade tradeFacade;

    @DubboReference
    private AccountFacade accountFacade;

    @DubboReference
    private PaymentFacade paymentFacade;

    private final PayMethodResolver payMethodResolver;
    private final PayVerifyService payVerifyService;

    /** C-1 聚合渲染（全部 ACTIVE USER 账户）；smoke 兼容：methods 含 BALANCE/CARD，BALANCE.accounts 列可用余额账户。 */
    public CashierDTO cashier(String orderId) {
        OrderDTO order = tradeFacade.getOrder(orderId).orThrow();
        List<AccountDTO> userAccounts = accountFacade.list(null).orThrow().stream()
                .filter(a -> "USER".equals(a.getType()) && "ACTIVE".equals(a.getStatus()))
                .toList();
        List<PayMethodDTO> methods = payMethodResolver.resolve(order, userAccounts, null);
        return buildDTO(order, methods);
    }

    /** C-1 指定付款人渲染（账户级可用性：冻结/余额不足/签约实时判定）。 */
    public CashierDTO cashierForPayer(String orderId, String accountId) {
        OrderDTO order = tradeFacade.getOrder(orderId).orThrow();
        AccountDTO payer = accountFacade.get(accountId).orThrow();
        List<PayMethodDTO> methods = payMethodResolver.resolve(order, List.of(payer), accountId);
        return buildDTO(order, methods);
    }

    private CashierDTO buildDTO(OrderDTO order, List<PayMethodDTO> methods) {
        CashierDTO dto = new CashierDTO();
        dto.setOrder(order);
        dto.setMethods(methods);
        dto.setMerchantName(order.getMerchantName());
        // 营销抵扣未落地（X-3/P-7 P2）：discount=0，实付=订单金额；预留扩展位
        dto.setDiscount(0L);
        dto.setActualAmount(order.getAmount());
        dto.setFreeVerifyLimit(payVerifyService.freeLimit());
        dto.setCurrency("CNY");
        return dto;
    }

    /**
     * P0 原始受理路径（<b>ride 红线：逐字节不变</b>）。直接委托支付核心，
     * BALANCE 同步受理→PROCESSING→SUCCESS，不引入验证前置/WAITING/异步回执。
     */
    public PayResultDTO pay(PayRequest request) {
        log.info("收银台支付受理 orderId={} method={}", request.getOrderId(), request.getMethod());
        return paymentFacade.pay(request).orThrow();
    }

    /** C-3 验证前置受理（新路径）：验证通过后以同 requestId 委托支付核心，BALANCE 同步语义不变。 */
    public PayResultDTO payVerified(VerifiedPayRequest req) {
        if (req.getRequestId() == null || req.getRequestId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "requestId 必填（幂等键）");
        }
        OrderDTO order = tradeFacade.getOrder(req.getOrderId()).orThrow();
        long amount = order.getAmount();
        String method = normalizeMethod(req.getMethod());
        String accountId = req.getAccountId();

        if (req.getVerifyToken() != null && !req.getVerifyToken().isBlank()) {
            if (!payVerifyService.consumeVerifyToken(req.getVerifyToken(), req.getOrderId(), amount, accountId)) {
                throw new BizException(ResultCode.FORBIDDEN, "支付验证无效或已过期，请重新验证");
            }
        } else if (req.getPayPassword() != null && !req.getPayPassword().isBlank()) {
            payVerifyService.verifyPasswordInline(accountId, null, req.getOrderId(), amount, req.getPayPassword());
        } else if (amount > payVerifyService.freeLimit()) {
            throw new BizException(ResultCode.FORBIDDEN, "超出小额免密限额，需支付验证");
        }

        PayRequest pay = new PayRequest();
        pay.setOrderId(req.getOrderId());
        pay.setMethod(method);
        pay.setAccountId(accountId);
        pay.setCardNo(req.getCardNo());
        pay.setRequestId(req.getRequestId());
        pay.setCallbackUrl(req.getCallbackUrl());
        log.info("收银台验证受理 orderId={} method={} requestId={}", req.getOrderId(), method, req.getRequestId());
        return paymentFacade.pay(pay).orThrow();
    }

    public VerifyResultDTO verify(VerifyRequest req) {
        return payVerifyService.verify(req);
    }

    public Boolean setPayPassword(SetPayPasswordRequest req) {
        return payVerifyService.setPayPassword(req);
    }

    public java.util.Map<String, Object> sendSms(String accountId, String memberId, String orderId) {
        return payVerifyService.sendSms(accountId, memberId, orderId);
    }

    private static String normalizeMethod(String method) {
        if (method == null || method.isBlank()) {
            return "BALANCE";
        }
        return "BANKCARD".equals(method) ? "CARD" : method;
    }
}