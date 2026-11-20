package com.qoder.payment.trade.service;

import com.qoder.payment.common.db.ShardRowMirror;
import com.qoder.payment.common.db.ShardRuntime;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.PriceChangeLogDTO;
import com.qoder.payment.trade.convert.OrderViews;
import com.qoder.payment.trade.dataobject.OrderDO;
import com.qoder.payment.trade.dataobject.OrderPriceLogDO;
import com.qoder.payment.trade.mapper.OrderMapper;
import com.qoder.payment.trade.mapper.OrderPriceLogMapper;
import com.qoder.tcc.IdempotentTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * T-6 改价（trade 侧）：未支付直接改额 + 已支付改价留痕（差额补退编排在 payment 域）。
 *
 * <p><b>幂等</b>：(PRICE_CHANGE,orderId:requestId) + order_price_log.request_id UNIQUE。
 * <p><b>竞态 CAS</b>：改价仅 CREATED 且 payment_id IS NULL（与受理 reserve 互为排他守卫）；
 * 受理侧 reserve 复核金额快照（TradeService），双向竞态零悬挂。
 * <p><b>fee 快照口径</b>：改价只动订单额；支付单 fee 仍在受理时按当时订单额快照，
 * 已受理支付单不受后续改价影响；merchant+out_trade_no 幂等键一字不动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceChangeService {

    private final OrderMapper orderMapper;
    private final OrderPriceLogMapper priceLogMapper;
    private final IdempotentTemplate idempotentTemplate;
    private final OrderViews views;
    private final ObjectProvider<ShardRuntime> shardRuntime;
    private final ObjectProvider<ShardRowMirror> shardRowMirror;

    /** 未支付改价：仅 CREATED（已支付拒绝，走 PaymentFacade.adjustPrice 差额补退）。 */
    public OrderDTO changePrice(String orderId, long newAmount, String reason, String requestId) {
        requireRequestId(requestId);
        requireCompositeKey(orderId, requestId);
        MoneyUtil.requireValid(newAmount);
        return idempotentTemplate.execute("PRICE_CHANGE", orderId + ":" + requestId, OrderDTO.class, () -> {
            OrderPriceLogDO existing = priceLogMapper.selectByRequest(requestId);
            if (existing != null) {
                return views.view(require(orderId));
            }
            OrderDO order = require(orderId);
            if (!"CREATED".equals(order.getStatus())) {
                if ("PAID".equals(order.getStatus()) || "PARTIAL_REFUNDED".equals(order.getStatus())) {
                    throw new BizException(ResultCode.BIZ_ERROR,
                            "已支付订单改价请走差额补退（POST /api/trade-plus/orders/{id}/adjust-price）: " + orderId);
                }
                throw new BizException(ResultCode.CONFLICT, "仅未支付 CREATED 订单可改价: " + orderId + " " + order.getStatus());
            }
            if (!"TRADE".equals(order.getBizType())
                    || (order.getProductType() != null && !"INSTANT".equals(order.getProductType()))) {
                throw new BizException(ResultCode.PARAM_ERROR, "仅即时到账商户交易可改价: " + orderId);
            }
            if (order.getMergeId() != null) {
                throw new BizException(ResultCode.CONFLICT, "合并单内订单不可改价（合并额快照守恒）: " + orderId);
            }
            if (order.getStageCount() != null && order.getStageCount() > 0) {
                throw new BizException(ResultCode.CONFLICT, "分阶段订单不可改价（Σ期额守恒）: " + orderId);
            }
            long old = order.getAmount();
            String direction = newAmount == old ? "NONE" : "UNPAID";
            if (newAmount != old && orderMapper.changePriceCas(orderId, newAmount) != 1) {
                throw new BizException(ResultCode.CONFLICT, "订单状态已变更（受理/关闭竞态），改价让位: " + orderId);
            }
            mirror(orderId);
            insertLog(orderId, old, newAmount, newAmount - old, direction, reason, requestId, null, null);
            log.info("未支付改价 orderId={} {}→{} requestId={}", orderId, old, newAmount, requestId);
            return views.view(require(orderId));
        });
    }

    /**
     * 已支付改价留痕（payment 域差额补退编排后调用）：只写审计，不动 amount/状态/fee。
     * old 取净价口径（订单额−已退额），与 adjustPrice 编排一致。
     */
    public OrderDTO markPriceChange(String orderId, long newAmount, String reason, String requestId,
                                    String direction, String refundId, String supplementOrderId) {
        requireRequestId(requestId);
        requireCompositeKey(orderId, requestId);
        MoneyUtil.requireValid(newAmount);
        return idempotentTemplate.execute("PRICE_CHANGE", orderId + ":" + requestId, OrderDTO.class, () -> {
            OrderPriceLogDO existing = priceLogMapper.selectByRequest(requestId);
            if (existing != null) {
                return views.view(require(orderId));
            }
            OrderDO order = require(orderId);
            long refunded = order.getRefundedAmount() == null ? 0L : order.getRefundedAmount();
            long old = order.getAmount() - refunded;
            long delta = Math.abs(newAmount - old);
            insertLog(orderId, old, newAmount, delta, direction, reason, requestId, refundId, supplementOrderId);
            orderMapper.bumpPriceChangeCount(orderId);
            mirror(orderId);
            log.info("已支付改价留痕 orderId={} 净价{}→{} direction={} refundId={} supplement={}",
                    orderId, old, newAmount, direction, refundId, supplementOrderId);
            return views.view(require(orderId));
        });
    }

    public List<PriceChangeLogDTO> listPriceLogs(String orderId) {
        require(orderId);
        List<PriceChangeLogDTO> out = new ArrayList<>();
        for (OrderPriceLogDO row : priceLogMapper.selectByOrder(orderId)) {
            PriceChangeLogDTO dto = new PriceChangeLogDTO();
            dto.setId(row.getId());
            dto.setOrderId(row.getOrderId());
            dto.setOldAmount(row.getOldAmount());
            dto.setNewAmount(row.getNewAmount());
            dto.setDelta(row.getDelta());
            dto.setDirection(row.getDirection());
            dto.setReason(row.getReason());
            dto.setOperator(row.getOperator());
            dto.setRequestId(row.getRequestId());
            dto.setRefundId(row.getRefundId());
            dto.setSupplementOrderId(row.getSupplementOrderId());
            dto.setCreatedAt(row.getCreatedAt());
            out.add(dto);
        }
        return out;
    }

    private void insertLog(String orderId, long old, long newAmount, long delta, String direction,
                           String reason, String requestId, String refundId, String supplementOrderId) {
        OrderPriceLogDO row = new OrderPriceLogDO();
        row.setId("PC" + priceLogMapper.nextId());
        row.setOrderId(orderId);
        row.setOldAmount(old);
        row.setNewAmount(newAmount);
        row.setDelta(delta);
        row.setDirection(direction);
        row.setReason(reason == null || reason.isBlank() ? "改价" : reason);
        row.setOperator("api");
        row.setRequestId(requestId);
        row.setRefundId(refundId);
        row.setSupplementOrderId(supplementOrderId);
        row.setCreatedAt(System.currentTimeMillis());
        priceLogMapper.insert(row);
    }

    private OrderDO require(String orderId) {
        OrderDO order = readYourWrite(() -> orderMapper.selectById(orderId));
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND, "订单不存在: " + orderId);
        }
        return order;
    }

    private static void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "requestId 必填（幂等键）");
        }
    }

    /** 复合幂等键 (PRICE_CHANGE,orderId:requestId) 不得超过 trade idempotent_record.biz_no(64)。 */
    private static void requireCompositeKey(String orderId, String requestId) {
        if (orderId == null || (orderId.length() + 1 + requestId.length()) > 64) {
            throw new BizException(ResultCode.PARAM_ERROR, "orderId:requestId 复合幂等键超长（≤64）");
        }
    }

    private void mirror(String orderId) {
        ShardRowMirror mirror = shardRowMirror.getIfAvailable();
        if (mirror != null) {
            mirror.mirror("trade_order", orderId);
        }
    }

    private <T> T readYourWrite(Supplier<T> action) {
        ShardRuntime runtime = shardRuntime.getIfAvailable();
        return runtime == null ? action.get() : runtime.readYourWrite(action);
    }
}