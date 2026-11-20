package com.qoder.payment.payment.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.payment.facade.payment.AdjustPriceDTO;
import com.qoder.payment.facade.payment.AdjustPriceRequest;
import com.qoder.payment.facade.payment.PayRequest;
import com.qoder.payment.facade.payment.PayResultDTO;
import com.qoder.payment.facade.payment.RefundRequest;
import com.qoder.payment.facade.payment.RefundResultDTO;
import com.qoder.payment.facade.trade.OrderCreateRequest;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.PriceChangeLogDTO;
import com.qoder.payment.facade.trade.TradeFacade;
import com.qoder.payment.payment.dataobject.PaymentDO;
import com.qoder.payment.payment.mapper.PaymentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * T-6 已支付改价（差额补退编排，payment 侧）。
 *
 * <p><b>语义</b>：净价口径 oldEffective = 订单额 − 已退额。
 * <ul>
 *   <li>下调（newAmount &lt; oldEffective）：差额经<b>退款单</b>原路退回（P-4 异步收敛/outbox 兜底不变），
 *       退后净价自动收敛为 newAmount；</li>
 *   <li>上调（newAmount &gt; oldEffective）：建<b>补差订单</b>（同商户，outTradeNo=ADJ-requestId
 *       复用 merchant+out_trade_no 唯一幂等）并 BALANCE 支付；</li>
 *   <li>等额：NONE 留痕不动账。</li>
 * </ul>
 *
 * <p><b>红线</b>：不改 trade_order.amount（已支付口径）/不触既有支付单 fee 快照（受理时已定格）；
 * merchant+out_trade_no 幂等键只用于新建补差单，既有键一字不动。留痕经 trade markPriceChange
 * (PRICE_CHANGE,orderId:requestId) 幂等。
 *
 * <p><b>幂等/崩溃收敛</b>：本编排不包本地事务（避免 TCC 分支嵌入外层事务），由三步各自幂等键组合：
 * 退款 requestId=ADJ:requestId、补差单 outTradeNo=ADJ-requestId、补差支付 requestId=SUP:requestId、
 * 留痕 requestId。任一步后崩溃 → 重试凭各自幂等键续驱收敛；重复请求经留痕预检返回首次结果。
 *
 * <p><b>大额闸门</b>：网关控制器对下调差额 ≥ 阈值执行 step-up + 双人复核（A0428），未复核绝不动账。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceAdjustService {

    private final PaymentMapper paymentMapper;
    private final PaymentService paymentService;

    @DubboReference
    private TradeFacade tradeFacade;

    public AdjustPriceDTO adjustPrice(AdjustPriceRequest request) {
        String requestId = request.getRequestId();
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "requestId 必填（幂等键）");
        }
        if (requestId.length() > 60) {
            throw new BizException(ResultCode.PARAM_ERROR, "requestId 过长（≤60，派生退款/补差幂等键需 ≤64）");
        }
        if (request.getOrderId() == null || request.getNewAmount() == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "orderId/newAmount 必填");
        }
        long newAmount = request.getNewAmount();
        MoneyUtil.requireValid(newAmount);
        OrderDTO order = tradeFacade.getOrder(request.getOrderId()).orThrow();
        // 幂等预检：留痕已存在 → 返回首次结果（不重复动账）
        List<PriceChangeLogDTO> logs = tradeFacade.listPriceLogs(order.getId()).orThrow();
        for (PriceChangeLogDTO logRow : logs) {
            if (requestId.equals(logRow.getRequestId())) {
                return replay(order, logRow);
            }
        }
        requireAdjustable(order);
        PaymentDO payment = paymentMapper.selectById(order.getPaymentId());
        if (payment == null || !"SUCCESS".equals(payment.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "订单支付单未成功，不可改价: " + order.getPaymentId());
        }
        if (!"SINGLE".equals(payment.getPayMode())) {
            throw new BizException(ResultCode.BIZ_ERROR, "组合/合并/分阶段支付单暂不支持已支付改价（请按腿/期维度处理）");
        }
        long refunded = order.getRefundedAmount() == null ? 0L : order.getRefundedAmount();
        long oldEffective = order.getAmount() - refunded;
        AdjustPriceDTO dto = new AdjustPriceDTO();
        dto.setOrderId(order.getId());
        dto.setOldEffective(oldEffective);
        dto.setNewAmount(newAmount);
        dto.setRepeated(false);
        if (newAmount < oldEffective) {
            long delta = oldEffective - newAmount;
            long remaining = payment.getAmount() - (payment.getRefundedAmount() == null ? 0L : payment.getRefundedAmount());
            if (delta > remaining) {
                throw new BizException(ResultCode.BIZ_ERROR, "改价差额超出支付单可退余额: " + delta + " > " + remaining);
            }
            RefundRequest refund = new RefundRequest();
            refund.setAmount(delta);
            refund.setRequestId("ADJ:" + requestId);
            RefundResultDTO refundResult = paymentService.refund(payment.getId(), refund);
            tradeFacade.markPriceChange(order.getId(), newAmount, request.getReason(), requestId,
                    "REFUND_DELTA", refundResult.getRefundId(), null).orThrow();
            dto.setDirection("DOWN");
            dto.setDelta(delta);
            dto.setRefundId(refundResult.getRefundId());
            dto.setRefundStatus(refundResult.getRefundStatus());
            log.info("已支付改价下调 orderId={} 净价{}→{} 差额退款 refundId={}", order.getId(), oldEffective, newAmount, refundResult.getRefundId());
        } else if (newAmount > oldEffective) {
            long delta = newAmount - oldEffective;
            if (request.getAccountId() == null || request.getAccountId().isBlank()) {
                throw new BizException(ResultCode.PARAM_ERROR, "上调改价需指定补差付款账户 accountId（BALANCE）");
            }
            OrderCreateRequest oc = new OrderCreateRequest();
            oc.setMerchantId(order.getMerchantId());
            oc.setSubject("改价补差-" + order.getId());
            oc.setAmount(delta);
            oc.setBizType("TRADE");
            oc.setProductType("INSTANT");
            // 复用 merchant+out_trade_no 唯一幂等：崩溃重试不产生孤儿补差单
            oc.setOutTradeNo("ADJ-" + requestId);
            OrderDTO supplement = tradeFacade.createOrder(oc).orThrow();
            PayRequest pay = new PayRequest();
            pay.setOrderId(supplement.getId());
            pay.setMethod("BALANCE");
            pay.setAccountId(request.getAccountId());
            pay.setRequestId("SUP:" + requestId);
            PayResultDTO payResult = paymentService.pay(pay);
            tradeFacade.markPriceChange(order.getId(), newAmount, request.getReason(), requestId,
                    "SUPPLEMENT", null, supplement.getId()).orThrow();
            dto.setDirection("UP");
            dto.setDelta(delta);
            dto.setSupplementOrderId(supplement.getId());
            dto.setSupplementPaymentId(payResult.getPayment().getId());
            log.info("已支付改价上调 orderId={} 净价{}→{} 补差单={} paymentId={}",
                    order.getId(), oldEffective, newAmount, supplement.getId(), payResult.getPayment().getId());
        } else {
            tradeFacade.markPriceChange(order.getId(), newAmount, request.getReason(), requestId,
                    "NONE", null, null).orThrow();
            dto.setDirection("NONE");
            dto.setDelta(0L);
        }
        OrderDTO after = tradeFacade.getOrder(order.getId()).orThrow();
        dto.setPriceChangeCount(after.getPriceChangeCount());
        return dto;
    }

    private static void requireAdjustable(OrderDTO order) {
        if (!"TRADE".equals(order.getBizType())
                || (order.getProductType() != null && !"INSTANT".equals(order.getProductType()))) {
            throw new BizException(ResultCode.PARAM_ERROR, "仅即时到账商户交易可已支付改价: " + order.getId());
        }
        if (!"PAID".equals(order.getStatus()) && !"PARTIAL_REFUNDED".equals(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "仅已支付订单可差额补退改价（未支付走 changePrice）: "
                    + order.getId() + " " + order.getStatus());
        }
        if (order.getMergeId() != null) {
            throw new BizException(ResultCode.CONFLICT, "合并单内订单不可单独改价: " + order.getId());
        }
        if (order.getStageCount() != null && order.getStageCount() > 0) {
            throw new BizException(ResultCode.CONFLICT, "分阶段订单不可已支付改价: " + order.getId());
        }
        if (order.getPaymentId() == null) {
            throw new BizException(ResultCode.CONFLICT, "订单无支付单关联: " + order.getId());
        }
    }

    private AdjustPriceDTO replay(OrderDTO order, PriceChangeLogDTO logRow) {
        AdjustPriceDTO dto = new AdjustPriceDTO();
        dto.setOrderId(order.getId());
        dto.setOldEffective(logRow.getOldAmount());
        dto.setNewAmount(logRow.getNewAmount());
        dto.setDelta(logRow.getDelta());
        dto.setDirection(switch (logRow.getDirection()) {
            case "REFUND_DELTA" -> "DOWN";
            case "SUPPLEMENT" -> "UP";
            default -> "NONE";
        });
        dto.setRefundId(logRow.getRefundId());
        dto.setSupplementOrderId(logRow.getSupplementOrderId());
        if (logRow.getSupplementOrderId() != null) {
            OrderDTO supplement = tradeFacade.getOrder(logRow.getSupplementOrderId()).orThrow();
            dto.setSupplementPaymentId(supplement.getPaymentId());
        }
        dto.setPriceChangeCount(order.getPriceChangeCount());
        dto.setRepeated(true);
        return dto;
    }
}