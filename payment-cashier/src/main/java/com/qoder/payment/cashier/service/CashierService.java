package com.qoder.payment.cashier.service;

import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.cashier.CashierDTO;
import com.qoder.payment.facade.cashier.PayMethodDTO;
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

    public CashierDTO cashier(String orderId) {
        OrderDTO order = tradeFacade.getOrder(orderId).orThrow();
        List<AccountDTO> userAccounts = accountFacade.list(null).orThrow().stream()
                .filter(a -> "USER".equals(a.getType()) && "ACTIVE".equals(a.getStatus()))
                .toList();

        PayMethodDTO balance = new PayMethodDTO();
        balance.setMethod("BALANCE");
        balance.setName("余额支付");
        balance.setHint("使用账户可用余额付款");
        balance.setAccounts(userAccounts);

        PayMethodDTO card = new PayMethodDTO();
        card.setMethod("CARD");
        card.setName("银行卡支付");
        card.setHint("模拟银行渠道，尾号 0000 的卡会被拒绝");
        card.setAccounts(List.of());

        CashierDTO dto = new CashierDTO();
        dto.setOrder(order);
        dto.setMethods(List.of(balance, card));
        return dto;
    }

    public PayResultDTO pay(PayRequest request) {
        log.info("收银台支付受理 orderId={} method={}", request.getOrderId(), request.getMethod());
        return paymentFacade.pay(request).orThrow();
    }
}