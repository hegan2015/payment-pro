package com.qoder.payment.trade.product;

import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.trade.OrderCreateRequest;
import com.qoder.payment.trade.dataobject.OrderDO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * T-3 即时到账产品（<b>现状状态机，全部钩子空操作</b>）。
 *
 * <p>CREATED→PAYING→PAID→(PARTIAL_REFUNDED/REFUNDED)；CREATED→CLOSED。
 * 付款即 D 付款方 / C 商户 + C SYS_FEE（支付核心既有分录模板，一字未改）。
 * 本类的存在只为把「默认产品」显式纳入路由表，绝不改变其行为。
 */
@Component
public class InstantProduct implements ProductStateMachine {

    @Override
    public TradeProduct product() {
        return TradeProduct.INSTANT;
    }

    @Override
    public List<String> states() {
        return List.of("CREATED", "PAYING", "PAID", "PARTIAL_REFUNDED", "REFUNDED", "CLOSED");
    }

    @Override
    public String description() {
        return "即时到账：付款即入商户账（默认产品，行为与改造前逐字节一致）";
    }

    @Override
    public String keyMerchantId(OrderCreateRequest request, MemberDTO seller) {
        return seller.getId();
    }

    @Override
    public void onCreate(OrderDO order, OrderCreateRequest request, MemberDTO seller) {
        // 空操作：默认产品不写任何产品子状态（payee/escrow 列保持 NULL）
    }

    @Override
    public void checkPayable(OrderDO order) {
        // 空操作
    }

    @Override
    public void onPaid(OrderDO order) {
        // 空操作
    }

    @Override
    public void checkRefundable(OrderDO order) {
        // 空操作
    }

    @Override
    public TimeoutAction timeoutAction(OrderDO order) {
        return "CREATED".equals(order.getStatus()) ? TimeoutAction.CLOSE_ORDER : TimeoutAction.NONE;
    }
}