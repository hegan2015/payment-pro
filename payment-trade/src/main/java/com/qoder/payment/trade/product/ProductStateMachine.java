package com.qoder.payment.trade.product;

import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.trade.OrderCreateRequest;
import com.qoder.payment.trade.dataobject.OrderDO;

import java.util.List;

/**
 * T-3 产品状态机 SPI：每个交易产品一个实现（Spring Bean），由 {@link ProductRouter} 按订单属性路由。
 *
 * <p>钩子全部落在交易域既有生命周期点上（下单 / TCC Try / TCC Confirm / TCC 退款 Try / 超时），
 * <b>INSTANT 实现的每个钩子都是空操作</b> ⇒ 默认产品路径与改造前逐字节一致。
 * 资金分录仍由支付核心（INSTANT/ESCROW 付款）与账务域 facade（担保确认/预授权完成）落地，
 * 交易域不直接写账务表。
 */
public interface ProductStateMachine {

    /** 超时动作：到期后该产品该状态应做什么（T-4 关闭 / T-1 自动确认 / 无）。 */
    enum TimeoutAction {
        NONE, CLOSE_ORDER, AUTO_CONFIRM_ESCROW
    }

    TradeProduct product();

    /** 产品状态集合（订单 status + 产品子状态，供控制台/验证断言）。 */
    List<String> states();

    String description();

    /**
     * 幂等键首列（trade_order.merchant_id）：INSTANT=卖家会员；
     * ESCROW=平台担保系统商户（真实卖家落 payee_merchant_id，同 outTradeNo 跨卖家由 payee 守卫拒绝）。
     */
    String keyMerchantId(OrderCreateRequest request, MemberDTO seller);

    /** 下单落库前的产品化装配（产品子状态、担保户、预授权额度等）。 */
    void onCreate(OrderDO order, OrderCreateRequest request, MemberDTO seller);

    /** TCC Try（PAY）产品守卫：不允许直接支付的产品在此拒绝（INSTANT 空操作）。 */
    void checkPayable(OrderDO order);

    /** TCC Confirm（PAY）后的产品状态推进（INSTANT 空操作；ESCROW 置 HOLD + 自动确认期限）。 */
    void onPaid(OrderDO order);

    /** TCC Try（REFUND）产品守卫（INSTANT 空操作；ESCROW 确认收货后禁止再从担保户退款）。 */
    void checkRefundable(OrderDO order);

    /** 到期动作判定（按订单当前状态）。 */
    TimeoutAction timeoutAction(OrderDO order);
}