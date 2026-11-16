package com.qoder.payment.payment.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.payment.dtx.TccCoordinator;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.accounting.AccountingTccContext;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.payment.facade.channel.ChannelTccContext;
import com.qoder.payment.facade.payment.PayRequest;
import com.qoder.payment.facade.payment.PayResultDTO;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.facade.payment.RefundRequest;
import com.qoder.payment.facade.payment.RefundResultDTO;
import com.qoder.payment.facade.risk.RiskCheckRequest;
import com.qoder.payment.facade.risk.RiskFacade;
import com.qoder.payment.facade.risk.RiskResultDTO;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.TradeFacade;
import com.qoder.payment.facade.trade.TradeTccContext;
import com.qoder.payment.payment.convert.PaymentConverter;
import com.qoder.payment.payment.dataobject.PaymentDO;
import com.qoder.payment.payment.dataobject.RefundDO;
import com.qoder.payment.payment.mapper.PaymentMapper;
import com.qoder.payment.payment.mapper.RefundMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 支付核心编排：
 * 幂等（request_id 唯一键先插）→ 风控 → TCC 一阶段同步 Try → 二阶段异步 Confirm，
 * 终态由 {@link PaymentCompletionListener} 事务性落库并写 outbox。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentMapper paymentMapper;
    private final RefundMapper refundMapper;
    private final TccCoordinator coordinator;

    @DubboReference
    private TradeFacade tradeFacade;

    @DubboReference
    private AccountFacade accountFacade;

    @DubboReference
    private RiskFacade riskFacade;

    @Value("${payment.fee-bps:60}")
    private int feeBps;

    public PayResultDTO pay(PayRequest request) {
        requireRequestId(request.getRequestId());
        String method = request.getMethod();
        if (!"BALANCE".equals(method) && !"CARD".equals(method)) {
            throw new BizException(ResultCode.PARAM_ERROR, "method 仅支持 BALANCE 或 CARD");
        }
        PaymentDO replay = paymentMapper.selectByRequestId(request.getRequestId());
        if (replay != null) {
            log.info("支付幂等命中 requestId={} paymentId={}", request.getRequestId(), replay.getId());
            return result(replay, true);
        }
        OrderDTO order = tradeFacade.getOrder(request.getOrderId()).orThrow();
        if (!"CREATED".equals(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "订单状态不可支付: " + order.getStatus());
        }
        AccountDTO merchantAccount = accountFacade.getByMember(order.getMerchantId()).orThrow();
        if ("BALANCE".equals(method)) {
            if (request.getAccountId() == null || request.getAccountId().isBlank()) {
                throw new BizException(ResultCode.PARAM_ERROR, "余额支付必须指定 accountId");
            }
            AccountDTO payer = accountFacade.get(request.getAccountId()).orThrow();
            if (!"ACTIVE".equals(payer.getStatus())) {
                throw new BizException(ResultCode.BIZ_ERROR, "付款账户已冻结: " + payer.getId());
            }
            if (payer.getBalance() < order.getAmount()) {
                throw new BizException(ResultCode.BIZ_ERROR, "账户余额不足: " + payer.getId());
            }
        } else if (request.getCardNo() == null || request.getCardNo().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "银行卡支付必须指定 cardNo");
        }

        long amount = order.getAmount();
        long fee = MoneyUtil.fee(amount, feeBps);
        PaymentDO payment = new PaymentDO();
        payment.setId("P" + paymentMapper.nextId());
        payment.setOrderId(order.getId());
        payment.setMerchantId(order.getMerchantId());
        payment.setMethod(method);
        payment.setPayerAccountId(request.getAccountId());
        payment.setCardNo(request.getCardNo());
        payment.setMerchantAccountId(merchantAccount.getId());
        payment.setAmount(amount);
        payment.setFee(fee);
        payment.setStatus("PROCESSING");
        payment.setRefundedAmount(0L);
        payment.setRefundedFee(0L);
        payment.setChannelBizNo("CARD".equals(method) ? payment.getId() : null);
        payment.setRequestId(request.getRequestId());
        payment.setCallbackUrl(request.getCallbackUrl());
        payment.setCreatedAt(System.currentTimeMillis());
        try {
            paymentMapper.insert(payment);
        } catch (DuplicateKeyException e) {
            PaymentDO existing = paymentMapper.selectByRequestId(request.getRequestId());
            log.info("支付幂等命中 requestId={} paymentId={}", request.getRequestId(), existing.getId());
            return result(existing, true);
        }

        RiskCheckRequest risk = new RiskCheckRequest();
        risk.setPayerId("BALANCE".equals(method) ? request.getAccountId() : "CARD:" + request.getCardNo());
        risk.setMerchantId(order.getMerchantId());
        risk.setAmount(amount);
        risk.setMethod(method);
        RiskResultDTO riskResult = riskFacade.check(risk).orThrow();
        if (!Boolean.TRUE.equals(riskResult.getPass())) {
            paymentMapper.finish(payment.getId(), "FAILED", riskResult.getReason());
            log.warn("风控拒绝 paymentId={} reason={}", payment.getId(), riskResult.getReason());
            throw new BizException(ResultCode.BIZ_ERROR, "风控拒绝: " + riskResult.getReason());
        }

        String xid = coordinator.begin("PAY", payment.getId());
        paymentMapper.updateXid(payment.getId(), xid);
        log.info("支付受理 paymentId={} orderId={} amount={} fee={} method={} requestId={} xid={}",
                payment.getId(), order.getId(), amount, fee, method, request.getRequestId(), xid);
        coordinator.registerBranch(xid, "accounting", "PAY", accountingContext(payment, order, false));
        if ("CARD".equals(method)) {
            coordinator.registerBranch(xid, "channel", "PAY", channelContext(payment.getId(), null, payment.getCardNo(), amount));
        }
        coordinator.registerBranch(xid, "trade", "PAY", tradeContext(order.getId(), payment.getId(), amount, payment.getId()));
        coordinator.tryAll(xid);
        coordinator.commitAsync(xid);
        return result(require(payment.getId()), false);
    }

    public RefundResultDTO refund(String paymentId, RefundRequest request) {
        requireRequestId(request.getRequestId());
        RefundDO replay = refundMapper.selectByRequestId(request.getRequestId());
        if (replay != null) {
            log.info("退款幂等命中 requestId={} refundId={}", request.getRequestId(), replay.getId());
            return refundResult(require(replay.getPaymentId()), replay.getAmount(), replay.getId());
        }
        MoneyUtil.requireValid(request.getAmount());
        PaymentDO payment = require(paymentId);
        if (!"SUCCESS".equals(payment.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "支付单未成功，不可退款: " + payment.getStatus());
        }
        long amount = request.getAmount();
        long remaining = payment.getAmount() - payment.getRefundedAmount();
        if (amount > remaining) {
            throw new BizException(ResultCode.BIZ_ERROR, "退款金额超出可退余额: " + remaining);
        }
        long feeRefund = amount == remaining
                ? payment.getFee() - payment.getRefundedFee()
                : (payment.getFee() * amount + payment.getAmount() / 2) / payment.getAmount();

        RefundDO refund = new RefundDO();
        refund.setId("R" + refundMapper.nextId());
        refund.setPaymentId(paymentId);
        refund.setAmount(amount);
        refund.setFeeRefund(feeRefund);
        refund.setRequestId(request.getRequestId());
        refund.setStatus("PROCESSING");
        refund.setBizNo(refund.getId());
        refund.setCreatedAt(System.currentTimeMillis());
        try {
            refundMapper.insert(refund);
        } catch (DuplicateKeyException e) {
            RefundDO existing = refundMapper.selectByRequestId(request.getRequestId());
            log.info("退款幂等命中 requestId={} refundId={}", request.getRequestId(), existing.getId());
            return refundResult(require(paymentId), existing.getAmount(), existing.getId());
        }

        String xid = coordinator.begin("REFUND", refund.getId());
        refundMapper.updateXid(refund.getId(), xid);
        log.info("退款受理 refundId={} paymentId={} amount={} feeRefund={} requestId={} xid={}",
                refund.getId(), paymentId, amount, feeRefund, request.getRequestId(), xid);
        coordinator.registerBranch(xid, "accounting", "REFUND", refundContext(payment, refund));
        if ("CARD".equals(payment.getMethod())) {
            coordinator.registerBranch(xid, "channel", "REFUND",
                    channelContext(refund.getId(), payment.getId(), payment.getCardNo(), amount));
        }
        coordinator.registerBranch(xid, "trade", "REFUND",
                tradeContext(payment.getOrderId(), paymentId, amount, refund.getId()));
        coordinator.tryAll(xid);
        coordinator.commitAsync(xid);
        return refundResult(require(paymentId), amount, refund.getId());
    }

    public List<PaymentDTO> listPayments() {
        return paymentMapper.selectAll().stream().map(PaymentConverter::toDTO).toList();
    }

    public PaymentDTO getPayment(String paymentId) {
        return PaymentConverter.toDTO(require(paymentId));
    }

    private AccountingTccContext accountingContext(PaymentDO payment, OrderDTO order, boolean reversed) {
        AccountingTccContext ctx = new AccountingTccContext();
        ctx.setBizNo(payment.getId());
        ctx.setRefId(order.getId());
        ctx.setSubject("支付-" + order.getSubject());
        ctx.setReversed(reversed);
        long net = payment.getAmount() - payment.getFee();
        List<TransferLineDTO> lines = new ArrayList<>();
        if ("BALANCE".equals(payment.getMethod())) {
            lines.add(line(payment.getPayerAccountId(), "D", payment.getAmount()));
        } else {
            lines.add(line("SYS_CASH", "D", payment.getAmount()));
        }
        lines.add(line(payment.getMerchantAccountId(), "C", net));
        if (payment.getFee() > 0) {
            lines.add(line("SYS_FEE", "C", payment.getFee()));
        }
        ctx.setLines(lines);
        return ctx;
    }

    private AccountingTccContext refundContext(PaymentDO payment, RefundDO refund) {
        AccountingTccContext ctx = new AccountingTccContext();
        ctx.setBizNo(refund.getBizNo());
        ctx.setRefId(payment.getId());
        ctx.setSubject("退款-" + payment.getId());
        ctx.setReversed(true);
        long net = refund.getAmount() - refund.getFeeRefund();
        List<TransferLineDTO> lines = new ArrayList<>();
        lines.add(line(payment.getMerchantAccountId(), "D", net));
        if (refund.getFeeRefund() > 0) {
            lines.add(line("SYS_FEE", "D", refund.getFeeRefund()));
        }
        if ("BALANCE".equals(payment.getMethod())) {
            lines.add(line(payment.getPayerAccountId(), "C", refund.getAmount()));
        } else {
            lines.add(line("SYS_CASH", "C", refund.getAmount()));
        }
        ctx.setLines(lines);
        return ctx;
    }

    private static ChannelTccContext channelContext(String bizNo, String origBizNo, String cardNo, long amount) {
        ChannelTccContext ctx = new ChannelTccContext();
        ctx.setBizNo(bizNo);
        ctx.setOrigBizNo(origBizNo);
        ctx.setCardNo(cardNo);
        ctx.setAmount(amount);
        return ctx;
    }

    private static TradeTccContext tradeContext(String orderId, String paymentId, long amount, String bizNo) {
        TradeTccContext ctx = new TradeTccContext();
        ctx.setOrderId(orderId);
        ctx.setPaymentId(paymentId);
        ctx.setAmount(amount);
        ctx.setBizNo(bizNo);
        return ctx;
    }

    private static TransferLineDTO line(String accountId, String dir, long amount) {
        TransferLineDTO line = new TransferLineDTO();
        line.setAccountId(accountId);
        line.setDir(dir);
        line.setAmount(amount);
        return line;
    }

    private PaymentDO require(String paymentId) {
        PaymentDO payment = paymentMapper.selectById(paymentId);
        if (payment == null) {
            throw new BizException(ResultCode.NOT_FOUND, "支付单不存在: " + paymentId);
        }
        return payment;
    }

    private static void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "requestId 必填（幂等键）");
        }
    }

    private static PayResultDTO result(PaymentDO payment, boolean repeated) {
        PayResultDTO result = new PayResultDTO();
        result.setPayment(PaymentConverter.toDTO(payment));
        result.setRepeated(repeated);
        return result;
    }

    private static RefundResultDTO refundResult(PaymentDO payment, long amount, String refundId) {
        RefundResultDTO result = new RefundResultDTO();
        result.setPayment(PaymentConverter.toDTO(payment));
        result.setRefundAmount(amount);
        result.setRefundId(refundId);
        return result;
    }
}