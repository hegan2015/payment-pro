package com.qoder.payment.trade.product;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.trade.OrderCreateRequest;
import com.qoder.payment.trade.dataobject.OrderDO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * T-2 预授权状态机（押金）：
 * <pre>
 * CREATED → AUTHORIZED（A-3 PREAUTH 冻结）→ 部分完成（仍 AUTHORIZED，子状态 PARTIAL_COMPLETED）
 *                                          → COMPLETED（累计完成额 = 冻结额）
 *                                          → CANCELLED（撤销，剩余冻结额全额解冻）
 * </pre>
 *
 * <p>冻结/解冻 <b>只调 account 域 A-3 facade</b>（经账务 TCC Try/Cancel 落 frozen 列），
 * available=balance−frozen 口径不变、冻结不产生分录 ⇒ 试算平衡不受影响。
 * 完成扣款走账务 post（D 买家 / C 卖家），金额 ≤ 冻结额−已完成额，差额解冻。
 * 预授权是独立资金冻结产品，<b>不经支付单</b>（故不可直接 trade.pay）。
 */
@Component
public class PreAuthProduct implements ProductStateMachine {

    @Override
    public TradeProduct product() {
        return TradeProduct.PREAUTH;
    }

    @Override
    public List<String> states() {
        return List.of("CREATED", "AUTHORIZED", "AUTHORIZED/PARTIAL_COMPLETED", "COMPLETED", "CANCELLED", "CLOSED");
    }

    @Override
    public String description() {
        return "预授权：冻结→完成扣款（≤冻结额，差额解冻）/撤销全额解冻";
    }

    @Override
    public String keyMerchantId(OrderCreateRequest request, MemberDTO seller) {
        return seller.getId();
    }

    @Override
    public void onCreate(OrderDO order, OrderCreateRequest request, MemberDTO seller) {
        // 冻结额 = 订单金额，落 preauth_record（不污染分片表 trade_order）
    }

    @Override
    public void checkPayable(OrderDO order) {
        throw new BizException(ResultCode.CONFLICT,
                "预授权订单不可直接支付，请先 preAuth 冻结再 completePreAuth/cancelPreAuth: " + order.getId());
    }

    @Override
    public void onPaid(OrderDO order) {
        // 预授权不经支付单，本钩子不可达
    }

    @Override
    public void checkRefundable(OrderDO order) {
        throw new BizException(ResultCode.CONFLICT, "预授权订单无支付单，资金回退请用 cancelPreAuth: " + order.getId());
    }

    @Override
    public TimeoutAction timeoutAction(OrderDO order) {
        return "CREATED".equals(order.getStatus()) ? TimeoutAction.CLOSE_ORDER : TimeoutAction.NONE;
    }
}