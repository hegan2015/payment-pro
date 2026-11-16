package com.qoder.payment.gateway.web;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.accounting.TopupRequest;
import com.qoder.payment.facade.cashier.CashierFacade;
import com.qoder.payment.facade.channel.ChannelFacade;
import com.qoder.payment.facade.member.MemberCreateRequest;
import com.qoder.payment.facade.member.MemberFacade;
import com.qoder.payment.facade.notify.NotifyFacade;
import com.qoder.payment.facade.notify.NotifyRequest;
import com.qoder.payment.facade.payment.PayRequest;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.facade.payment.PaymentFacade;
import com.qoder.payment.facade.payment.RefundRequest;
import com.qoder.payment.facade.recon.ReconFacade;
import com.qoder.payment.facade.recon.ReconReportDTO;
import com.qoder.payment.facade.settle.SettleFacade;
import com.qoder.payment.facade.trade.OrderCreateRequest;
import com.qoder.payment.facade.trade.TradeFacade;
import com.qoder.payment.gateway.service.HealthService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** REST 入口：薄适配层，业务全部经 Dubbo 路由到各域服务。 */
@RestController
@RequestMapping("/api")
public class ApiController {

    @DubboReference
    private MemberFacade memberFacade;

    @DubboReference
    private AccountFacade accountFacade;

    @DubboReference
    private AccountingFacade accountingFacade;

    @DubboReference
    private TradeFacade tradeFacade;

    @DubboReference
    private CashierFacade cashierFacade;

    @DubboReference
    private PaymentFacade paymentFacade;

    @DubboReference
    private ChannelFacade channelFacade;

    @DubboReference
    private SettleFacade settleFacade;

    @DubboReference
    private NotifyFacade notifyFacade;

    @DubboReference
    private ReconFacade reconFacade;

    private final HealthService healthService;

    @Value("${payment.fee-bps:60}")
    private int feeBps;

    public ApiController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    public Object health() {
        return healthService.list();
    }

    @GetMapping("/stats")
    public Object stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("feeBps", feeBps);
        stats.put("members", count(() -> memberFacade.list().orThrow().size()));
        stats.put("accounts", count(() -> accountFacade.list(null).orThrow().size()));
        stats.put("orders", count(() -> tradeFacade.listOrders().orThrow().size()));
        long volume = 0;
        long refunded = 0;
        int paid = 0;
        try {
            for (PaymentDTO p : paymentFacade.listPayments().orThrow()) {
                if ("SUCCESS".equals(p.getStatus()) || "REFUNDED".equals(p.getStatus())) {
                    volume += p.getAmount();
                    refunded += p.getRefundedAmount() == null ? 0 : p.getRefundedAmount();
                    paid++;
                }
            }
        } catch (Exception ignored) {
        }
        stats.put("paidPayments", paid);
        stats.put("totalVolume", volume);
        stats.put("totalRefunded", refunded);
        stats.put("settle", orEmpty(() -> settleFacade.summary().orThrow()));
        stats.put("trial", orEmpty(() -> accountingFacade.trial().orThrow()));
        return stats;
    }

    @GetMapping("/members")
    public ResponseEntity<Object> members() {
        return GatewaySupport.of(memberFacade.list());
    }

    @PostMapping("/members")
    public ResponseEntity<Object> createMember(@RequestBody MemberCreateRequest request) {
        return GatewaySupport.of(memberFacade.create(request));
    }

    @PostMapping("/members/{id}/status")
    public ResponseEntity<Object> memberStatus(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return GatewaySupport.of(memberFacade.updateStatus(id, str(body.get("status"))));
    }

    @GetMapping("/accounts")
    public ResponseEntity<Object> accounts(@RequestParam(required = false) String memberId) {
        return GatewaySupport.of(accountFacade.list(memberId));
    }

    @PostMapping("/accounts")
    public ResponseEntity<Object> createAccount(@RequestBody Map<String, Object> body) {
        return GatewaySupport.of(accountFacade.create(str(body.get("memberId"))));
    }

    @PostMapping("/accounts/{id}/status")
    public ResponseEntity<Object> accountStatus(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return GatewaySupport.of(accountFacade.updateStatus(id, str(body.get("status"))));
    }

    @PostMapping("/accounting/internal/topup")
    public ResponseEntity<Object> topup(@RequestBody TopupRequest request) {
        Result<Long> result = accountingFacade.topup(request);
        if (!result.isSuccess()) {
            return GatewaySupport.of(result);
        }
        return ResponseEntity.ok(Map.of("balance", result.getData()));
    }

    @GetMapping("/accounting/accounts/{id}/entries")
    public ResponseEntity<Object> entries(@PathVariable String id,
                                          @RequestParam(defaultValue = "100") int limit) {
        return GatewaySupport.of(accountingFacade.entries(id, limit));
    }

    @GetMapping("/accounting/accounts/{id}/balance")
    public ResponseEntity<Object> balance(@PathVariable String id) {
        return GatewaySupport.of(accountingFacade.balance(id));
    }

    @GetMapping("/accounting/transfers/{bizNo}")
    public ResponseEntity<Object> transfer(@PathVariable String bizNo) {
        return GatewaySupport.of(accountingFacade.transfer(bizNo));
    }

    @GetMapping("/accounting/trial")
    public ResponseEntity<Object> trial() {
        return GatewaySupport.of(accountingFacade.trial());
    }

    @GetMapping("/orders")
    public ResponseEntity<Object> orders() {
        return GatewaySupport.of(tradeFacade.listOrders());
    }

    @PostMapping("/orders")
    public ResponseEntity<Object> createOrder(@RequestBody OrderCreateRequest request) {
        Result<com.qoder.payment.facade.trade.OrderDTO> result = tradeFacade.createOrder(request);
        if (!result.isSuccess()) {
            return GatewaySupport.of(result);
        }
        return ResponseEntity.ok(Map.of("order", result.getData()));
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<Object> order(@PathVariable String id) {
        return GatewaySupport.of(tradeFacade.getOrder(id));
    }

    @PostMapping("/orders/{id}/close")
    public ResponseEntity<Object> closeOrder(@PathVariable String id) {
        return GatewaySupport.of(tradeFacade.closeOrder(id));
    }

    @GetMapping("/cashier")
    public ResponseEntity<Object> cashier(@RequestParam String orderId) {
        return GatewaySupport.of(cashierFacade.cashier(orderId));
    }

    @PostMapping("/cashier/pay")
    public ResponseEntity<Object> cashierPay(@RequestBody PayRequest request) {
        request.setMethod(normalizeMethod(request.getMethod()));
        return GatewaySupport.of(cashierFacade.pay(request));
    }

    @GetMapping("/payments")
    public ResponseEntity<Object> payments() {
        return GatewaySupport.of(paymentFacade.listPayments());
    }

    @GetMapping("/payments/{id}")
    public ResponseEntity<Object> payment(@PathVariable String id) {
        return GatewaySupport.of(paymentFacade.getPayment(id));
    }

    @PostMapping("/payments/{id}/refund")
    public ResponseEntity<Object> refund(@PathVariable String id, @RequestBody RefundRequest request) {
        return GatewaySupport.of(paymentFacade.refund(id, request));
    }

    @GetMapping("/channel/trades")
    public ResponseEntity<Object> channelTrades() {
        return GatewaySupport.of(channelFacade.listTrades());
    }

    @GetMapping("/settle/summary")
    public ResponseEntity<Object> settleSummary() {
        return GatewaySupport.of(settleFacade.summary());
    }

    @GetMapping("/settle/records")
    public ResponseEntity<Object> settleRecords(@RequestParam(required = false) String merchantId) {
        return GatewaySupport.of(settleFacade.records(merchantId));
    }

    @GetMapping("/notify/records")
    public ResponseEntity<Object> notifyRecords(@RequestParam(required = false) String bizId) {
        return GatewaySupport.of(notifyFacade.records(bizId));
    }

    @PostMapping("/notify/send")
    public ResponseEntity<Object> notifySend(@RequestBody NotifyRequest request) {
        return GatewaySupport.of(notifyFacade.send(request));
    }

    @GetMapping("/notify/{id}")
    public ResponseEntity<Object> notifyGet(@PathVariable String id) {
        return GatewaySupport.of(notifyFacade.get(id));
    }

    @GetMapping("/recon/latest")
    public ResponseEntity<Object> reconLatest() {
        Result<ReconReportDTO> result = reconFacade.latest();
        if (!result.isSuccess()) {
            return GatewaySupport.of(result);
        }
        return ResponseEntity.ok(result.getData() == null ? Map.of() : result.getData());
    }

    @PostMapping("/recon/run")
    public ResponseEntity<Object> reconRun() {
        return GatewaySupport.of(reconFacade.run());
    }

    static String normalizeMethod(String method) {
        return "BANKCARD".equals(method) ? "CARD" : method;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static int count(CountSupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return -1;
        }
    }

    private static Object orEmpty(DataSupplier supplier) {
        try {
            Object value = supplier.get();
            return value == null ? Map.of() : value;
        } catch (Exception e) {
            return Map.of();
        }
    }

    @FunctionalInterface
    private interface CountSupplier {
        int get();
    }

    @FunctionalInterface
    private interface DataSupplier {
        Object get();
    }
}