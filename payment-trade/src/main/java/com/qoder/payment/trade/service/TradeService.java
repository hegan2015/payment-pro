package com.qoder.payment.trade.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.tcc.IdempotentTemplate;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.member.MemberFacade;
import com.qoder.payment.facade.trade.OrderCreateRequest;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.TradeTccContext;
import com.qoder.payment.trade.convert.OrderConverter;
import com.qoder.payment.trade.dataobject.OrderDO;
import com.qoder.payment.trade.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 交易单生命周期：CREATED → PAYING → PAID → (REFUNDED)；CREATED → CLOSED。
 * 状态流转全部以 CAS（WHERE status=旧态）保证并发安全。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeService {

    private final OrderMapper orderMapper;
    private final IdempotentTemplate idempotentTemplate;

    @DubboReference
    private MemberFacade memberFacade;

    public OrderDTO createOrder(OrderCreateRequest request) {
        MoneyUtil.requireValid(request.getAmount());
        String subject = request.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > 128) {
            throw new BizException(ResultCode.PARAM_ERROR, "subject 必填且不超过 128 字");
        }
        MemberDTO merchant = memberFacade.get(request.getMerchantId()).orThrow();
        if (!"MERCHANT".equals(merchant.getType())) {
            throw new BizException(ResultCode.PARAM_ERROR, "下单会员必须是商户");
        }
        if (!"ACTIVE".equals(merchant.getStatus())) {
            throw new BizException(ResultCode.BIZ_ERROR, "商户已冻结: " + merchant.getId());
        }
        String outTradeNo = request.getOutTradeNo();
        if (outTradeNo != null && outTradeNo.isBlank()) {
            outTradeNo = null;
        }
        if (outTradeNo != null) {
            OrderDO existing = orderMapper.selectByOutTradeNo(merchant.getId(), outTradeNo);
            if (existing != null) {
                log.info("下单幂等命中 merchantId={} outTradeNo={} orderId={}", merchant.getId(), outTradeNo, existing.getId());
                return OrderConverter.toDTO(existing);
            }
        }
        OrderDO order = new OrderDO();
        order.setId("O" + orderMapper.nextId());
        order.setMerchantId(merchant.getId());
        order.setMerchantName(merchant.getName());
        order.setOutTradeNo(outTradeNo);
        order.setBizType(request.getBizType() == null || request.getBizType().isBlank() ? "TRADE" : request.getBizType());
        order.setSubject(subject);
        order.setAmount(request.getAmount());
        order.setStatus("CREATED");
        order.setRefundedAmount(0L);
        order.setCreatedAt(System.currentTimeMillis());
        try {
            orderMapper.insert(order);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            OrderDO existing = orderMapper.selectByOutTradeNo(merchant.getId(), outTradeNo);
            if (existing == null) {
                throw e;
            }
            return OrderConverter.toDTO(existing);
        }
        log.info("订单创建 orderId={} merchantId={} amount={} subject={}", order.getId(), merchant.getId(), request.getAmount(), subject);
        return OrderConverter.toDTO(order);
    }

    public List<OrderDTO> listOrders() {
        return orderMapper.selectAll().stream().map(OrderConverter::toDTO).toList();
    }

    public OrderDTO getOrder(String orderId) {
        return OrderConverter.toDTO(require(orderId));
    }

    public OrderDTO closeOrder(String orderId) {
        require(orderId);
        if (orderMapper.close(orderId, System.currentTimeMillis()) == 0) {
            throw new BizException(ResultCode.CONFLICT, "仅 CREATED 状态订单可关闭: " + orderId);
        }
        log.info("订单关闭 orderId={}", orderId);
        return OrderConverter.toDTO(require(orderId));
    }

    public OrderDTO refundMark(String orderId, long amount, String bizNo) {
        MoneyUtil.requireValid(amount);
        return idempotentTemplate.execute("REFUND_MARK", bizNo, OrderDTO.class, () -> {
            OrderDO order = require(orderId);
            if (!refundable(order.getStatus())) {
                throw new BizException(ResultCode.CONFLICT, "订单未支付成功，不可退款: " + orderId);
            }
            long refunded = order.getRefundedAmount() + amount;
            if (refunded > order.getAmount()) {
                throw new BizException(ResultCode.BIZ_ERROR, "退款金额超出已付金额: " + orderId);
            }
            String status = refunded == order.getAmount() ? "REFUNDED" : "PARTIAL_REFUNDED";
            orderMapper.updateRefund(orderId, refunded, status);
            log.info("订单退款标记 orderId={} refunded={} status={}", orderId, refunded, status);
            return OrderConverter.toDTO(require(orderId));
        });
    }

    /** TCC Try（PAY）：CREATED → PAYING 预留订单。 */
    public void reserve(TradeTccContext ctx) {
        OrderDO order = require(ctx.getOrderId());
        if ("PAID".equals(order.getStatus()) || "REFUNDED".equals(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "订单已支付: " + ctx.getOrderId());
        }
        if ("CLOSED".equals(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "订单已关闭: " + ctx.getOrderId());
        }
        if (orderMapper.reserve(ctx.getOrderId(), ctx.getPaymentId()) == 0) {
            throw new BizException(ResultCode.CONFLICT, "订单正在支付中: " + ctx.getOrderId());
        }
        log.info("订单预留(TCC Try) orderId={} paymentId={}", ctx.getOrderId(), ctx.getPaymentId());
    }

    /** TCC Confirm（PAY）：PAYING → PAID。 */
    public void markPaid(TradeTccContext ctx) {
        OrderDO order = require(ctx.getOrderId());
        if ("PAID".equals(order.getStatus())) {
            return;
        }
        orderMapper.markPaid(ctx.getOrderId(), System.currentTimeMillis());
        log.info("订单标记已付(TCC Confirm) orderId={}", ctx.getOrderId());
    }

    /** TCC Cancel（PAY）：PAYING → CREATED 释放预留。 */
    public void release(TradeTccContext ctx) {
        orderMapper.release(ctx.getOrderId(), ctx.getPaymentId());
        log.info("订单释放预留(TCC Cancel) orderId={} paymentId={}", ctx.getOrderId(), ctx.getPaymentId());
    }

    /** TCC Try（REFUND）：校验可退额度，不落状态。 */
    public void checkRefundable(TradeTccContext ctx) {
        MoneyUtil.requireValid(ctx.getAmount());
        OrderDO order = require(ctx.getOrderId());
        if (!refundable(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "订单未支付成功，不可退款: " + ctx.getOrderId());
        }
        if (order.getRefundedAmount() + ctx.getAmount() > order.getAmount()) {
            throw new BizException(ResultCode.BIZ_ERROR, "退款金额超出已付金额: " + ctx.getOrderId());
        }
    }

    private static boolean refundable(String status) {
        return "PAID".equals(status) || "PARTIAL_REFUNDED".equals(status) || "REFUNDED".equals(status);
    }

    private OrderDO require(String orderId) {
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND, "订单不存在: " + orderId);
        }
        return order;
    }
}