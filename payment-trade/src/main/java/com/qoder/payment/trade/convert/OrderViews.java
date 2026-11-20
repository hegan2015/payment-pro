package com.qoder.payment.trade.convert;

import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.trade.dataobject.OrderDO;
import com.qoder.payment.trade.dataobject.PreAuthRecordDO;
import com.qoder.payment.trade.mapper.PreAuthMapper;
import com.qoder.payment.trade.product.TradeProduct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * T-3 订单出参装配：基础字段仍由 {@link OrderConverter}（BeanUtils 全量拷贝，既有字段一字未改）产出，
 * 本类只 <b>追加</b> 产品只读字段（预授权明细来自单库 preauth_record，批量装配避免 N+1）。
 *
 * <p>INSTANT 订单除 productType/expireAt 外新增字段恒为 null ⇒ ride {@code trade.query} 回查口径不变。
 */
@Component
@RequiredArgsConstructor
public class OrderViews {

    private final PreAuthMapper preAuthMapper;
    /** P2-H T-5：分阶段计划装配（仅 stageCount>0 订单回查，默认订单零额外读写）。 */
    private final com.qoder.payment.trade.mapper.StagePayMapper stagePayMapper;

    public OrderDTO view(OrderDO order) {
        if (order == null) {
            return null;
        }
        OrderDTO dto = OrderConverter.toDTO(order);
        normalize(dto);
        if (TradeProduct.PREAUTH == TradeProduct.of(order.getProductType())) {
            decorate(dto, preAuthMapper.selectByOrderId(order.getId()));
        }
        decorateStages(dto);
        return dto;
    }

    public List<OrderDTO> views(List<OrderDO> orders) {
        List<OrderDTO> out = new ArrayList<>(orders.size());
        List<String> preauthIds = new ArrayList<>();
        for (OrderDO order : orders) {
            OrderDTO dto = OrderConverter.toDTO(order);
            normalize(dto);
            out.add(dto);
            if (TradeProduct.PREAUTH == TradeProduct.of(order.getProductType())) {
                preauthIds.add(order.getId());
            }
            decorateStages(dto);
        }
        if (!preauthIds.isEmpty()) {
            Map<String, PreAuthRecordDO> byOrder = new HashMap<>();
            for (PreAuthRecordDO record : preAuthMapper.selectByOrderIds(preauthIds)) {
                byOrder.put(record.getOrderId(), record);
            }
            for (OrderDTO dto : out) {
                decorate(dto, byOrder.get(dto.getId()));
            }
        }
        return out;
    }

    /** T-5 additive：分阶段计划额列表（无计划恒 null ⇒ ride trade.query 口径不变）。 */
    private void decorateStages(OrderDTO dto) {
        if (dto.getStageCount() == null || dto.getStageCount() <= 0) {
            return;
        }
        List<Long> amounts = new ArrayList<>();
        for (com.qoder.payment.trade.dataobject.OrderStagePlanDO plan : stagePayMapper.selectPlan(dto.getId())) {
            amounts.add(plan.getAmount());
        }
        dto.setStageAmounts(amounts);
    }

    private static void normalize(OrderDTO dto) {
        if (dto.getProductType() == null || dto.getProductType().isBlank()) {
            dto.setProductType(TradeProduct.INSTANT.code());
        }
    }

    private static void decorate(OrderDTO dto, PreAuthRecordDO record) {
        if (record == null) {
            return;
        }
        dto.setPreauthAccountId(record.getAccountId());
        dto.setPreauthAmount(record.getAuthAmount());
        dto.setPreauthCompleted(record.getCompletedAmount());
        dto.setPreauthStatus(record.getStatus());
        dto.setPreauthFreezeId(record.getFreezeId());
    }
}