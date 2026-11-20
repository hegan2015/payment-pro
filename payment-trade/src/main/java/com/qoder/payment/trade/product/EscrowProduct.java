package com.qoder.payment.trade.product;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.trade.OrderCreateRequest;
import com.qoder.payment.trade.dataobject.OrderDO;
import com.qoder.payment.trade.mapper.OrderMapper;
import com.qoder.payment.trade.service.EscrowHoldAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * T-1 担保交易状态机：
 * <pre>
 * CREATED → PAYING → PAID(escrow_status=HOLD) → 确认收货/超时自动确认 → PAID(CONFIRMED/AUTO_CONFIRMED)
 * </pre>
 *
 * <p>订单主状态保持 PAID（支付-交易对账口径不变），担保子状态在 {@code escrow_status}。
 * 资金：付款 D 买家 / C 担保户(净额) + C SYS_FEE（支付核心既有模板，手续费支付时即收）；
 * 确认收货 D 担保户 / C 卖家（净额，见 EscrowService）。卖家在确认前 <b>分文未入</b>。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EscrowProduct implements ProductStateMachine {

    private final EscrowHoldAccountService holdAccountService;
    private final OrderMapper orderMapper;
    private final DynamicConfig dynamicConfig;

    @Override
    public TradeProduct product() {
        return TradeProduct.ESCROW;
    }

    @Override
    public List<String> states() {
        return List.of("CREATED", "PAYING", "PAID/HOLD", "PAID/CONFIRMED", "PAID/AUTO_CONFIRMED",
                "PARTIAL_REFUNDED", "REFUNDED", "CLOSED");
    }

    @Override
    public String description() {
        return "担保交易：付款入平台担保户，确认收货（或超时自动确认）后打款卖家";
    }

    @Override
    public String keyMerchantId(OrderCreateRequest request, MemberDTO seller) {
        return holdAccountService.ensure().memberId();
    }

    @Override
    public void onCreate(OrderDO order, OrderCreateRequest request, MemberDTO seller) {
        EscrowHoldAccountService.Hold hold = holdAccountService.ensure();
        // 幂等键首列 = 担保系统商户；真实卖家落 payee_merchant_id（同 outTradeNo 跨卖家由 payee 守卫拒绝）
        order.setMerchantId(hold.memberId());
        order.setMerchantName(holdAccountService.holdMerchantName());
        order.setPayeeMerchantId(seller.getId());
    }

    @Override
    public void checkPayable(OrderDO order) {
        if (order.getPayeeMerchantId() == null || order.getPayeeMerchantId().isBlank()) {
            throw new BizException(ResultCode.BIZ_ERROR, "担保订单缺少卖家（payeeMerchantId）: " + order.getId());
        }
    }

    @Override
    public void onPaid(OrderDO order) {
        // 0 天 = 立即到期（运维/验证用最短窗口）；缺省 7 天，配置热更只影响新支付完结的担保单
        int days = Math.max(0, dynamicConfig.getInt(TradeProductKeys.ESCROW_AUTO_CONFIRM_DAYS,
                TradeProductKeys.DEFAULT_ESCROW_AUTO_CONFIRM_DAYS));
        long deadline = System.currentTimeMillis() + days * 24L * 60L * 60L * 1000L;
        int held = orderMapper.holdEscrow(order.getId(), deadline);
        if (held > 0) {
            log.info("担保中 orderId={} 自动确认期限={} ({}天)", order.getId(), deadline, days);
        }
    }

    @Override
    public void checkRefundable(OrderDO order) {
        // 确认收货后资金已打款卖家，禁止再从担保户退款（否则会挪用他单担保资金）
        if (!"HOLD".equals(order.getEscrowStatus())) {
            throw new BizException(ResultCode.CONFLICT,
                    "担保资金已确认收货打款卖家，不可再从担保户退款: " + order.getId() + " escrowStatus=" + order.getEscrowStatus());
        }
    }

    @Override
    public TimeoutAction timeoutAction(OrderDO order) {
        if ("CREATED".equals(order.getStatus())) {
            return TimeoutAction.CLOSE_ORDER;
        }
        if ("PAID".equals(order.getStatus()) && "HOLD".equals(order.getEscrowStatus())) {
            return TimeoutAction.AUTO_CONFIRM_ESCROW;
        }
        return TimeoutAction.NONE;
    }
}