package com.qoder.payment.payment.service;

import com.qoder.payment.common.db.IdGenerator;
import com.qoder.payment.common.db.ShardRowMirror;
import com.qoder.payment.common.db.ShardRuntime;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.accounting.AccountingTccContext;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.payment.facade.payment.PayResultDTO;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.facade.payment.StagePayRequest;
import com.qoder.payment.facade.risk.RiskCheckRequest;
import com.qoder.payment.facade.risk.RiskFacade;
import com.qoder.payment.facade.risk.RiskResultDTO;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.TradeFacade;
import com.qoder.payment.facade.trade.TradeTccContext;
import com.qoder.payment.payment.convert.PaymentConverter;
import com.qoder.payment.payment.dataobject.PaymentDO;
import com.qoder.payment.payment.mapper.PaymentMapper;
import com.qoder.tcc.GlobalStatus;
import com.qoder.tcc.TccCoordinator;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * T-5 分阶段付款编排（payment 侧）：每期一笔独立支付单（payMode=STAGE，BALANCE 同步语义）。
 *
 * <p><b>守恒</b>：期额必须等于 trade 侧阶段计划额（计划创建时 Σ期额=订单额，误差 0）；
 * 顺序支付（stageSeq=paidStage+1，trade 侧 CAS 守卫）；全款后 paid_stage=stage_count、
 * paid_amount=amount、订单 PAID。
 *
 * <p><b>幂等</b>：payment.request_id UNIQUE + trade 侧 stage_pay_log
 * UNIQUE(order_id,stage_seq)/UNIQUE(payment_id)/UNIQUE(request_id) 三重占位。
 * 每期 fee 按本期额受理快照（feeFor 口径不变，与整单一次付的 fee 相互独立）。
 *
 * <p><b>边界</b>：STAGE 单不支持 COMBO 腿/营销抵扣（保守，后续增量）；退款须全款完成后
 * 按普通退款走（订单 PAID 才可退，trade checkRefundable 既有守卫）；撤销仅最新已付期（P-5 扩展）。
 */
@Slf4j
@Service
public class StagePayOrchestrator {

    private final PaymentMapper paymentMapper;
    private final TccCoordinator coordinator;
    private final FeeGateway feeGateway;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<ShardRuntime> shardRuntime;
    private final ObjectProvider<IdGenerator> idGenerator;
    private final ObjectProvider<ShardRowMirror> shardRowMirror;
    private final ObjectProvider<PaymentRequestIndex> requestIndex;

    @DubboReference
    private TradeFacade tradeFacade;

    @DubboReference
    private AccountFacade accountFacade;

    @DubboReference
    private RiskFacade riskFacade;

    public StagePayOrchestrator(PaymentMapper paymentMapper, TccCoordinator coordinator, FeeGateway feeGateway,
                                JdbcTemplate jdbc, ObjectProvider<ShardRuntime> shardRuntime,
                                ObjectProvider<IdGenerator> idGenerator, ObjectProvider<ShardRowMirror> shardRowMirror,
                                ObjectProvider<PaymentRequestIndex> requestIndex) {
        this.paymentMapper = paymentMapper;
        this.coordinator = coordinator;
        this.feeGateway = feeGateway;
        this.jdbc = jdbc;
        this.shardRuntime = shardRuntime;
        this.idGenerator = idGenerator;
        this.shardRowMirror = shardRowMirror;
        this.requestIndex = requestIndex;
    }

    public PayResultDTO payStage(StagePayRequest request) {
        if (request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "requestId 必填（幂等键）");
        }
        if (request.getOrderId() == null || request.getStageSeq() == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "orderId/stageSeq 必填");
        }
        PaymentDO replay = readYourWrite(() -> paymentMapper.selectByRequestId(request.getRequestId()));
        if (replay != null) {
            log.info("阶段支付幂等命中 requestId={} paymentId={}", request.getRequestId(), replay.getId());
            return result(replay, true);
        }
        OrderDTO order = tradeFacade.getOrder(request.getOrderId()).orThrow();
        int stageCount = order.getStageCount() == null ? 0 : order.getStageCount();
        if (stageCount < 2) {
            throw new BizException(ResultCode.BIZ_ERROR, "订单无分阶段计划（先创建阶段计划）: " + order.getId());
        }
        int seq = request.getStageSeq();
        int paidStage = order.getPaidStage() == null ? 0 : order.getPaidStage();
        if (seq < 1 || seq > stageCount) {
            throw new BizException(ResultCode.PARAM_ERROR, "期序非法: " + seq);
        }
        if (seq != paidStage + 1) {
            throw new BizException(ResultCode.CONFLICT, "分阶段须顺序支付: 当前已付 " + paidStage + " 期，请求第 " + seq + " 期");
        }
        if (order.getRefundedAmount() != null && order.getRefundedAmount() > 0) {
            throw new BizException(ResultCode.CONFLICT, "订单存在退款，不可继续分阶段支付: " + order.getId());
        }
        if (order.getMergeId() != null) {
            throw new BizException(ResultCode.CONFLICT, "合并单内订单不可分阶段支付: " + order.getId());
        }
        String expectStatus = seq == 1 ? "CREATED" : "PAID";
        if (!expectStatus.equals(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "订单状态不可阶段支付: " + order.getStatus());
        }
        List<Long> plan = order.getStageAmounts();
        if (plan == null || plan.size() != stageCount) {
            throw new BizException(ResultCode.BIZ_ERROR, "阶段计划明细缺失: " + order.getId());
        }
        long planAmount = plan.get(seq - 1);
        long amount = request.getAmount() == null ? planAmount : request.getAmount();
        if (amount != planAmount) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "阶段金额不守恒: 请求=" + amount + " ≠ 计划=" + planAmount + "（Σ期额=订单额）");
        }
        MoneyUtil.requireValid(amount);
        if (request.getAccountId() == null || request.getAccountId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "阶段支付必须指定 accountId（BALANCE）");
        }
        AccountDTO payer = accountFacade.get(request.getAccountId()).orThrow();
        if (!"ACTIVE".equals(payer.getStatus())) {
            throw new BizException(ResultCode.BIZ_ERROR, "付款账户已冻结: " + payer.getId());
        }
        if (payer.getBalance() < amount) {
            throw new BizException(ResultCode.BIZ_ERROR, "账户余额不足: " + payer.getId());
        }
        AccountDTO merchantAccount = accountFacade.getByMember(order.getMerchantId()).orThrow();
        long fee = feeGateway.feeFor(order.getMerchantId(), "TRADE", "BALANCE", amount);

        PaymentDO payment = new PaymentDO();
        payment.setId(newPaymentId());
        payment.setOrderId(order.getId());
        payment.setMerchantId(order.getMerchantId());
        payment.setBizType("TRADE");
        payment.setMethod("BALANCE");
        payment.setPayMode("STAGE");
        payment.setStageSeq(seq);
        payment.setPayerAccountId(request.getAccountId());
        payment.setMerchantAccountId(merchantAccount.getId());
        payment.setAmount(amount);
        payment.setFee(fee);
        payment.setStatus("PROCESSING");
        payment.setRefundedAmount(0L);
        payment.setRefundedFee(0L);
        payment.setRequestId(request.getRequestId());
        payment.setCallbackUrl(request.getCallbackUrl());
        payment.setCreatedAt(System.currentTimeMillis());
        PaymentDO replayed = insertPayment(payment);
        if (replayed != null) {
            return result(replayed, true);
        }

        // 风控（业务失败留 FAILED 痕，与单腿口径一致；订单/期留痕未动，TCC 未开启）
        RiskCheckRequest risk = new RiskCheckRequest();
        risk.setPayerId(request.getAccountId());
        risk.setMerchantId(order.getMerchantId());
        risk.setAmount(amount);
        risk.setMethod("BALANCE");
        RiskResultDTO riskResult = riskFacade.check(risk).orThrow();
        if (!Boolean.TRUE.equals(riskResult.getPass())) {
            paymentMapper.finish(payment.getId(), "FAILED", riskResult.getReason());
            mirror(payment.getId());
            MetricsSupport.payFail("BALANCE", "TRADE");
            log.warn("阶段支付风控拒绝 paymentId={} reason={}", payment.getId(), riskResult.getReason());
            throw new BizException(ResultCode.BIZ_ERROR, "风控拒绝: " + riskResult.getReason());
        }

        String xid = coordinator.begin("PAY", payment.getId());
        // 一阶段宽限（与 PaymentService.tryGrace 同一护栏）
        jdbc.update("UPDATE dtx_transaction SET next_retry_at=? WHERE xid=? AND status='TRYING'",
                System.currentTimeMillis() + 10_000L, xid);
        paymentMapper.updateXid(payment.getId(), xid);
        mirror(payment.getId());
        log.info("阶段支付受理 paymentId={} orderId={} stage={}/{} amount={} fee={} requestId={} xid={}",
                payment.getId(), order.getId(), seq, stageCount, amount, fee, request.getRequestId(), xid);
        coordinator.registerBranch(xid, "accounting", "PAY", accountingContext(payment, order));
        TradeTccContext stage = new TradeTccContext();
        stage.setOrderId(order.getId());
        stage.setPaymentId(payment.getId());
        stage.setAmount(amount);
        // bizNo 承载调用方 requestId（stage_pay_log.request_id UNIQUE 幂等键）
        stage.setBizNo(request.getRequestId());
        stage.setStageSeq(seq);
        coordinator.registerBranch(xid, "trade", "STAGE_PAY", stage);
        GlobalStatus preTry = coordinator.status(xid);
        if (preTry == GlobalStatus.CANCELLED || preTry == GlobalStatus.CANCELLING) {
            log.warn("阶段受理让位（事务已回滚，防悬挂）xid={} status={}", xid, preTry);
            throw new BizException(ResultCode.CONFLICT, "阶段支付已回滚，受理让位: " + payment.getId());
        }
        coordinator.tryAll(xid);
        coordinator.commitAsync(xid);
        return result(require(payment.getId()), false);
    }

    private AccountingTccContext accountingContext(PaymentDO payment, OrderDTO order) {
        AccountingTccContext ctx = new AccountingTccContext();
        ctx.setBizNo(payment.getId());
        ctx.setRefId(order.getId());
        ctx.setSubject("分阶段支付-" + order.getSubject() + "-第" + payment.getStageSeq() + "期");
        ctx.setReversed(false);
        long net = payment.getAmount() - payment.getFee();
        List<TransferLineDTO> lines = new ArrayList<>();
        lines.add(line(payment.getPayerAccountId(), "D", payment.getAmount()));
        lines.add(line(payment.getMerchantAccountId(), "C", net));
        if (payment.getFee() > 0) {
            lines.add(line("SYS_FEE", "C", payment.getFee()));
        }
        ctx.setLines(lines);
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
        PaymentDO payment = readYourWrite(() -> paymentMapper.selectById(paymentId));
        if (payment == null) {
            throw new BizException(ResultCode.NOT_FOUND, "支付单不存在: " + paymentId);
        }
        return payment;
    }

    private String newPaymentId() {
        ShardRuntime runtime = shardRuntime.getIfAvailable();
        if (runtime != null && runtime.isActive()) {
            return idGenerator.getObject().next("P");
        }
        return "P" + paymentMapper.nextId();
    }

    private PaymentDO insertPayment(PaymentDO payment) {
        PaymentRequestIndex gate = requestIndex.getIfAvailable();
        if (gate != null) {
            return gate.insertPayment(payment);
        }
        try {
            paymentMapper.insert(payment);
            return null;
        } catch (DuplicateKeyException e) {
            return paymentMapper.selectByRequestId(payment.getRequestId());
        }
    }

    private void mirror(String paymentId) {
        ShardRowMirror mirror = shardRowMirror.getIfAvailable();
        if (mirror != null) {
            mirror.mirror("payment", paymentId);
        }
    }

    private <T> T readYourWrite(Supplier<T> action) {
        ShardRuntime runtime = shardRuntime.getIfAvailable();
        return runtime == null ? action.get() : runtime.readYourWrite(action);
    }

    private PayResultDTO result(PaymentDO payment, boolean repeated) {
        PayResultDTO result = new PayResultDTO();
        PaymentDTO dto = PaymentConverter.toDTO(payment);
        result.setPayment(dto);
        result.setRepeated(repeated);
        return result;
    }
}