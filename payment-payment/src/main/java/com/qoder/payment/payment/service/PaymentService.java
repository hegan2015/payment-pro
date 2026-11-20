package com.qoder.payment.payment.service;

import com.qoder.payment.common.config.ConfigKeys;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.db.IdGenerator;
import com.qoder.payment.common.db.ShardRowMirror;
import com.qoder.payment.common.db.ShardRuntime;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.tcc.GlobalStatus;
import com.qoder.tcc.TccCoordinator;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.accounting.AccountingTccContext;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.payment.facade.channel.ChannelTccContext;
import com.qoder.payment.facade.member.MemberCreateRequest;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.member.MemberFacade;
import com.qoder.payment.facade.payment.FundPayRequest;
import com.qoder.payment.facade.payment.PayRequest;
import com.qoder.payment.facade.payment.PayResultDTO;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.facade.payment.RefundDTO;
import com.qoder.payment.facade.payment.RefundRequest;
import com.qoder.payment.facade.payment.RefundResultDTO;
import com.qoder.payment.facade.risk.RiskCheckRequest;
import com.qoder.payment.facade.risk.RiskFacade;
import com.qoder.payment.facade.risk.RiskResultDTO;
import com.qoder.payment.facade.trade.OrderCreateRequest;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.TradeFacade;
import com.qoder.payment.facade.trade.TradeTccContext;
import com.qoder.payment.payment.convert.PaymentConverter;
import com.qoder.payment.payment.dataobject.LegAllocDO;
import com.qoder.payment.payment.dataobject.PaymentDO;
import com.qoder.payment.payment.dataobject.PaymentLegDO;
import com.qoder.payment.payment.dataobject.RefundDO;
import com.qoder.payment.payment.mapper.PaymentLegMapper;
import com.qoder.payment.payment.mapper.PaymentMapper;
import com.qoder.payment.payment.mapper.RefundMapper;
import com.qoder.tcc.TccContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

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
    private final PaymentReconcileService reconcileService;
    private final DynamicConfig dynamicConfig;
    /** P1-I：同步事务一阶段宽限（防 recover 与在途 tryAll 竞态误回滚，见 tryGrace）。 */
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    /** P1-I：计费接 settle FeeCalculator（降级本地口径）、P-6 路由预检、P-2 组合编排、CHALLENGE 续付、P-4 退款补单。 */
    private final FeeGateway feeGateway;
    private final PaymentRouteService routeService;
    private final ComboPayService comboPayService;
    private final ChallengeService challengeService;
    private final RefundReconcileService refundReconcileService;
    private final PaymentLegMapper legMapper;
    /** P2-E P-7 营销抵扣编排（无营销诉求时纯内存短路，零 RPC / 零额外延迟，ride 红线）。 */
    private final MarketingPayService marketingPayService;
    /** I-5b：分片未装配（local / enabled=false）时全部为 null，走改造前原路径。 */
    private final ObjectProvider<ShardRuntime> shardRuntime;
    private final ObjectProvider<IdGenerator> idGenerator;
    private final ObjectProvider<ShardRowMirror> shardRowMirror;
    private final ObjectProvider<PaymentRequestIndex> requestIndex;

    @DubboReference
    private TradeFacade tradeFacade;

    @DubboReference
    private AccountFacade accountFacade;

    @DubboReference
    private MemberFacade memberFacade;

    @DubboReference
    private RiskFacade riskFacade;

    /**
     * 异步渠道开关（CH-1）：默认 false=同步模拟银行，路径与增量前完全一致（tryAll→commitAsync）。
     * 为 true 时银行卡支付 tryAll 后进入 dtx WAITING（资金冻结未过账），
     * 由渠道终态回执/查询补单经 resumeCommit/resumeCancel 收敛。须与 channel 域同开关。
     */
    @Value("${payment.channel.async:false}")
    private boolean channelAsync;

    public PayResultDTO pay(PayRequest request) {
        requireRequestId(request.getRequestId());
        boolean combo = request.getLegs() != null && !request.getLegs().isEmpty();
        // P2-E P-7：营销诉求判定是纯内存读 additive 字段（零 RPC / 零 DB / 零额外延迟）。
        // 无营销诉求（ride 全部路径）时 marketing=false，下方所有营销分支一律跳过 ⇒ 行为逐字节不变。
        boolean marketing = marketingPayService.requested(request);
        String method = request.getMethod();
        if (!combo && !"BALANCE".equals(method) && !"CARD".equals(method)) {
            throw new BizException(ResultCode.PARAM_ERROR, "method 仅支持 BALANCE 或 CARD");
        }
        if (marketing) {
            marketingPayService.requireEnabled();
            if (!combo && !"BALANCE".equals(method)) {
                throw new BizException(ResultCode.PARAM_ERROR,
                        "营销抵扣需付款 BALANCE 户承接补贴（CARD 单腿请改用含 BALANCE 腿的组合支付）");
            }
        }
        PaymentDO replay = readYourWrite(() -> paymentMapper.selectByRequestId(request.getRequestId()));
        if (replay != null) {
            log.info("支付幂等命中 requestId={} paymentId={}", request.getRequestId(), replay.getId());
            return result(replay, true);
        }
        OrderDTO order = tradeFacade.getOrder(request.getOrderId()).orThrow();
        if (!"CREATED".equals(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "订单状态不可支付: " + order.getStatus());
        }
        // ==================== P2-H T-5 additive 守卫（默认订单 stageCount/mergeId 恒 null ⇒ ride 路径零影响） ====================
        if (order.getStageCount() != null && order.getStageCount() > 0) {
            throw new BizException(ResultCode.CONFLICT, "分阶段订单请使用分阶段付款(payStage): " + order.getId());
        }
        if (order.getMergeId() != null) {
            com.qoder.payment.facade.trade.MergeOrderDTO merge = tradeFacade.getMergeOrder(order.getMergeId()).orThrow();
            if ("CREATED".equals(merge.getStatus()) || "PAYING".equals(merge.getStatus())) {
                throw new BizException(ResultCode.CONFLICT, "订单在合并单中，请使用合并支付(payMerge): " + order.getId());
            }
        }
        AccountDTO merchantAccount = accountFacade.getByMember(order.getMerchantId()).orThrow();
        if (combo) {
            // P-2 组合支付：金额守恒/多腿 TCC/整体原子见 ComboPayService（fee 按订单总额受理快照）
            long comboFee = feeGateway.feeFor(order.getMerchantId(), "TRADE", "COMBO", order.getAmount());
            return comboPayService.payCombo(request, order, merchantAccount, comboFee);
        }
        if ("BALANCE".equals(method)) {
            if (request.getAccountId() == null || request.getAccountId().isBlank()) {
                throw new BizException(ResultCode.PARAM_ERROR, "余额支付必须指定 accountId");
            }
            AccountDTO payer = accountFacade.get(request.getAccountId()).orThrow();
            if (!"ACTIVE".equals(payer.getStatus())) {
                throw new BizException(ResultCode.BIZ_ERROR, "付款账户已冻结: " + payer.getId());
            }
            if (payer.getBalance() < order.getAmount() && !marketing) {
                // P-7：有营销诉求时补贴先入本户，余额充足性延后到补贴到账后校验（见下方 apply 之后）
                throw new BizException(ResultCode.BIZ_ERROR, "账户余额不足: " + payer.getId());
            }
        } else if (request.getCardNo() == null || request.getCardNo().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "银行卡支付必须指定 cardNo");
        }

        long amount = order.getAmount();
        // P-6 支付路由（CARD 受理前预检，失败关闭不落单不悬挂；BALANCE 无渠道不经过路由）。
        // 渠道级择优/故障切换由 channel 域 CH-3 Router 实现，本域只透传选中渠道，不重复造。
        String routedChannel = "CARD".equals(method) ? routeService.routeForPay(amount, request.getCardNo()) : null;
        // I-2/P1-I：计费接 settle FeeCalculator（三层费率，缺省 60bps 与改造前一致，§0.7 口径红线）；
        // settle 不可达降级本地 DynamicConfig 同公式。fee 随支付单落库即受理快照——
        // 后续二阶段账务/结算/退款均读 payment.fee，配置热更不影响在途单。
        long fee = feeGateway.feeFor(order.getMerchantId(), "TRADE", method, amount);
        PaymentDO payment = new PaymentDO();
        payment.setId(newPaymentId());
        payment.setOrderId(order.getId());
        payment.setMerchantId(order.getMerchantId());
        payment.setBizType("TRADE");
        payment.setMethod(method);
        payment.setPayMode("SINGLE");
        payment.setChannelCode(routedChannel);
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
        PaymentDO replayed = insertPayment(payment);
        if (replayed != null) {
            log.info("支付幂等命中 requestId={} paymentId={}", request.getRequestId(), replayed.getId());
            return result(replayed, true);
        }

        // ==================== P2-E P-7 营销抵扣编排（marketing=false 时整段跳过，零影响） ====================
        if (marketing) {
            try {
                marketingPayService.apply(request, order, payment.getId(), request.getAccountId());
                // 补贴已入付款户（D SYS_MARKETING / C 用户户），此时才校验余额充足性（净实付=订单额−Σ抵扣）
                AccountDTO after = accountFacade.get(request.getAccountId()).orThrow();
                if (!"ACTIVE".equals(after.getStatus())) {
                    throw new BizException(ResultCode.BIZ_ERROR, "付款账户已冻结: " + after.getId());
                }
                if (after.getBalance() < amount) {
                    throw new BizException(ResultCode.BIZ_ERROR,
                            "账户余额不足（营销抵扣后仍需 " + amount + "，可用 " + after.getBalance() + "）: " + after.getId());
                }
            } catch (RuntimeException e) {
                marketingPayService.rollbackQuietly(payment.getId(), "受理失败: " + e.getMessage());
                paymentMapper.finish(payment.getId(), "FAILED", MarketingPayService.shortReason("营销抵扣编排失败: " + e.getMessage()));
                mirror("payment", payment.getId());
                MetricsSupport.payFail(method, payment.getBizType());
                throw e;
            }
        }

        try {
            RiskCheckRequest risk = new RiskCheckRequest();
            risk.setPayerId("BALANCE".equals(method) ? request.getAccountId() : "CARD:" + request.getCardNo());
            risk.setMerchantId(order.getMerchantId());
            risk.setAmount(amount);
            risk.setMethod(method);
            RiskResultDTO riskResult = riskFacade.check(risk).orThrow();
            if (!Boolean.TRUE.equals(riskResult.getPass())) {
                // CHALLENGE 续付（R-4 × C-3）：仅 CHALLENGE 一档且持有效续付授权才放行；
                // 其余分级（REJECT/LIMIT/MANUAL_REVIEW）与无授权 CHALLENGE 一律受理前拒绝（现状保守口径）。
                String payerKey = ChallengeService.payerKey(method, request.getAccountId(), request.getCardNo());
                boolean challengeContinue = "CHALLENGE".equals(riskResult.getAction()) && challengeService.enabled()
                        && challengeService.tryConsumeGrant(request.getRequestId(), order.getId(), payerKey, amount);
                if (!challengeContinue) {
                    if ("CHALLENGE".equals(riskResult.getAction()) && challengeService.enabled()) {
                        challengeService.recordChallenge(request.getRequestId(), order.getId(), payerKey, amount, method);
                    }
                    paymentMapper.finish(payment.getId(), "FAILED", riskResult.getReason());
                    mirror("payment", payment.getId());
                    log.warn("风控拒绝 paymentId={} reason={}", payment.getId(), riskResult.getReason());
                    MetricsSupport.payFail(method, payment.getBizType());
                    // P-7：风控拒绝须退回已核销营销权益（资金反向），绝不留下「支付失败但补贴已发」
                    if (marketing) {
                        marketingPayService.rollbackQuietly(payment.getId(), "风控拒绝: " + riskResult.getReason());
                    }
                    throw new BizException(ResultCode.BIZ_ERROR, "风控拒绝: " + riskResult.getReason());
                }
                log.info("CHALLENGE 续付授权放行 paymentId={} requestId={}", payment.getId(), request.getRequestId());
            }

            // 异步渠道（银行卡 + 开关开启）：开启即 WAITING，杜绝「Try 完成但未及声明等待」被 recover 直接提交的崩溃窗口
            boolean awaiting = "CARD".equals(method) && channelAsync;
            String xid = awaiting ? coordinator.beginAwaiting("PAY", payment.getId()) : coordinator.begin("PAY", payment.getId());
            tryGrace(xid);
            paymentMapper.updateXid(payment.getId(), xid);
            mirror("payment", payment.getId());
            log.info("支付受理 paymentId={} orderId={} amount={} fee={} method={} requestId={} xid={} awaiting={}",
                    payment.getId(), order.getId(), amount, fee, method, request.getRequestId(), xid, awaiting);
            coordinator.registerBranch(xid, "accounting", "PAY", accountingContext(payment, order, false));
            if ("CARD".equals(method)) {
                ChannelTccContext channelCtx = channelContext(payment.getId(), null, payment.getCardNo(), amount);
                channelCtx.setChannelCode(routedChannel);
                coordinator.registerBranch(xid, "channel", "PAY", channelCtx);
            }
            coordinator.registerBranch(xid, "trade", "PAY", tradeContext(order.getId(), payment.getId(), amount, payment.getId()));
            // P-5 防悬挂守卫：关闭/回滚可能在 begin→registerBranch 窗口内以「零分支」抢先完成
            // （driveCancel 无分支可取消即置 CANCELLED），此后绝不再 Try——否则冻结/预留落在已终态事务上。
            GlobalStatus preTry = coordinator.status(xid);
            if (preTry == GlobalStatus.CANCELLED || preTry == GlobalStatus.CANCELLING) {
                log.warn("受理让位（事务已回滚/关闭，防悬挂）xid={} status={}", xid, preTry);
                throw new BizException(ResultCode.CONFLICT, "支付已关闭/回滚，受理让位: " + payment.getId());
            }
            coordinator.tryAll(xid);
            if (awaiting) {
                // 资金仍冻结、订单仍 PAYING、支付单 PROCESSING；等渠道终态回执/补单查询驱动二阶段
                log.info("银行卡异步渠道已受理，等待渠道终态 paymentId={} xid={}", payment.getId(), xid);
            } else {
                coordinator.commitAsync(xid);
            }
            return result(require(payment.getId()), false);
        } catch (RuntimeException e) {
            // P-7：受理链路任何异常（含 TCC Try 失败/受理让位）都退回已核销营销权益；
            // 无营销诉求时本 catch 只做原样重抛，行为与改造前逐字节一致。
            if (marketing) {
                marketingPayService.rollbackQuietly(payment.getId(), "受理失败: " + e.getMessage());
            }
            throw e;
        }
    }

    /**
     * 资金操作支付（充值/提现）：区别于商户交易，无手续费、无渠道，走系统资金商户 SYS_FUND。
     * 仍走完整 TCC（账务+交易分支）与完结监听，故清结算单/对账自然齐备。
     */
    public PayResultDTO fundPay(FundPayRequest request) {
        requireRequestId(request.getRequestId());
        String bizType = request.getBizType();
        if (!"TOPUP".equals(bizType) && !"WITHDRAW".equals(bizType)) {
            throw new BizException(ResultCode.PARAM_ERROR, "bizType 仅支持 TOPUP 或 WITHDRAW");
        }
        MoneyUtil.requireValid(request.getAmount());
        PaymentDO replay = readYourWrite(() -> paymentMapper.selectByRequestId(request.getRequestId()));
        if (replay != null) {
            log.info("资金支付幂等命中 requestId={} paymentId={}", request.getRequestId(), replay.getId());
            return result(replay, true);
        }
        MemberDTO fundMerchant = ensureFundMerchant();
        AccountDTO fundAccount = accountFacade.getByMember(fundMerchant.getId()).orThrow();
        AccountDTO userAccount = accountFacade.get(request.getAccountId()).orThrow();
        if (!"ACTIVE".equals(userAccount.getStatus())) {
            throw new BizException(ResultCode.BIZ_ERROR, "账户已冻结: " + userAccount.getId());
        }
        long amount = request.getAmount();
        if ("WITHDRAW".equals(bizType) && userAccount.getBalance() < amount) {
            throw new BizException(ResultCode.BIZ_ERROR, "账户余额不足: " + userAccount.getId());
        }
        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setMerchantId(fundMerchant.getId());
        oc.setSubject(("TOPUP".equals(bizType) ? "充值" : "提现") + "-" + userAccount.getId());
        oc.setAmount(amount);
        oc.setOutTradeNo(request.getRequestId());
        oc.setBizType(bizType);
        OrderDTO order = tradeFacade.createOrder(oc).orThrow();

        PaymentDO payment = new PaymentDO();
        payment.setId(newPaymentId());
        payment.setOrderId(order.getId());
        payment.setMerchantId(fundMerchant.getId());
        payment.setBizType(bizType);
        payment.setMethod("BALANCE");
        payment.setPayMode("SINGLE");
        payment.setPayerAccountId(userAccount.getId());
        payment.setMerchantAccountId(fundAccount.getId());
        payment.setAmount(amount);
        payment.setFee(0L);
        payment.setStatus("PROCESSING");
        payment.setRefundedAmount(0L);
        payment.setRefundedFee(0L);
        payment.setRequestId(request.getRequestId());
        payment.setCallbackUrl(request.getCallbackUrl());
        payment.setCreatedAt(System.currentTimeMillis());
        PaymentDO replayed = insertPayment(payment);
        if (replayed != null) {
            log.info("资金支付幂等命中 requestId={} paymentId={}", request.getRequestId(), replayed.getId());
            return result(replayed, true);
        }
        String xid = coordinator.begin("PAY", payment.getId());
        tryGrace(xid);
        paymentMapper.updateXid(payment.getId(), xid);
        mirror("payment", payment.getId());
        log.info("资金支付受理 paymentId={} bizType={} accountId={} amount={} requestId={} xid={}",
                payment.getId(), bizType, userAccount.getId(), amount, request.getRequestId(), xid);
        coordinator.registerBranch(xid, "accounting", "PAY", fundAccountingContext(payment, bizType));
        coordinator.registerBranch(xid, "trade", "PAY", tradeContext(order.getId(), payment.getId(), amount, payment.getId()));
        // P-5 防悬挂守卫：关闭/回滚可能在 begin→registerBranch 窗口内以「零分支」抢先完成
        // （driveCancel 无分支可取消即置 CANCELLED），此后绝不再 Try——否则冻结/预留落在已终态事务上。
        GlobalStatus preTry = coordinator.status(xid);
        if (preTry == GlobalStatus.CANCELLED || preTry == GlobalStatus.CANCELLING) {
            log.warn("受理让位（事务已回滚/关闭，防悬挂）xid={} status={}", xid, preTry);
            throw new BizException(ResultCode.CONFLICT, "支付已关闭/回滚，受理让位: " + payment.getId());
        }
        coordinator.tryAll(xid);
        coordinator.commitAsync(xid);
        return result(require(payment.getId()), false);
    }

    /** 系统资金商户 SYS_FUND 懒创建（MERCHANT），供充值/提现资金单挂载，幂等。 */
    private synchronized MemberDTO ensureFundMerchant() {
        MemberDTO existing = memberFacade.list().orThrow().stream()
                .filter(m -> "SYS_FUND".equals(m.getName()) && "MERCHANT".equals(m.getType()))
                .findFirst().orElse(null);
        if (existing == null) {
            MemberCreateRequest r = new MemberCreateRequest();
            r.setName("SYS_FUND");
            r.setType("MERCHANT");
            existing = memberFacade.create(r).orThrow();
            log.info("创建系统资金商户 SYS_FUND memberId={}", existing.getId());
        }
        if (accountFacade.list(existing.getId()).orThrow().isEmpty()) {
            accountFacade.create(existing.getId()).orThrow();
        }
        return existing;
    }

    private AccountingTccContext fundAccountingContext(PaymentDO payment, String bizType) {
        AccountingTccContext ctx = new AccountingTccContext();
        ctx.setBizNo(payment.getId());
        ctx.setRefId(payment.getOrderId());
        ctx.setReversed(false);
        long amount = payment.getAmount();
        List<TransferLineDTO> lines = new ArrayList<>();
        if ("TOPUP".equals(bizType)) {
            ctx.setSubject("充值");
            lines.add(line("SYS_CASH", "D", amount));
            lines.add(line(payment.getPayerAccountId(), "C", amount));
        } else {
            ctx.setSubject("提现");
            lines.add(line(payment.getPayerAccountId(), "D", amount));
            lines.add(line("SYS_CASH", "C", amount));
        }
        ctx.setLines(lines);
        return ctx;
    }

    public RefundResultDTO refund(String paymentId, RefundRequest request) {
        requireRequestId(request.getRequestId());
        RefundDO replay = readYourWrite(() -> refundMapper.selectByRequestId(request.getRequestId()));
        if (replay != null) {
            log.info("退款幂等命中 requestId={} refundId={}", request.getRequestId(), replay.getId());
            return refundResult(require(replay.getPaymentId()), replay.getAmount(), replay.getId(), replay.getStatus());
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
        refund.setId(newRefundId());
        refund.setPaymentId(paymentId);
        refund.setAmount(amount);
        refund.setFeeRefund(feeRefund);
        refund.setRequestId(request.getRequestId());
        refund.setStatus("PROCESSING");
        refund.setBizNo(refund.getId());
        refund.setCreatedAt(System.currentTimeMillis());
        // P-4：CARD 退款渠道查询键（异步回执/补单收敛用；BALANCE 无渠道为 null）
        // P2-H additive：MERGE_COMBO（合并+组合叠加）同样按腿分摊（Σ腿=合并额守恒不变）
        boolean combo = PaymentCompletionListener.isComboMode(payment.getPayMode());
        if ("CARD".equals(payment.getMethod()) && !combo) {
            refund.setChannelOrderNo(refund.getId());
        }
        // P-2 组合退款：腿级水位填充分摊（受理时落库快照，完结监听据此累加腿已退）
        List<LegAllocDO> legAllocs = null;
        if (combo) {
            List<PaymentLegDO> legs = legMapper.selectByPaymentId(paymentId);
            legAllocs = comboPayService.allocateRefund(legs, amount, feeRefund);
            refund.setLegAllocJson(TccContextUtil.toJson(legAllocs));
        }
        RefundDO replayedRefund = insertRefund(refund);
        if (replayedRefund != null) {
            log.info("退款幂等命中 requestId={} refundId={}", request.getRequestId(), replayedRefund.getId());
            return refundResult(require(paymentId), replayedRefund.getAmount(), replayedRefund.getId(), replayedRefund.getStatus());
        }
        // P-5 撤销竞态守卫：落单后复读支付单状态（撤销 CAS 可能已胜出），非 SUCCESS 即失败关闭，
        // 此时 TCC 未开启、资金未动，退款单置 FAILED 不留悬挂（仅竞态窗口生效，正常路径不变）。
        PaymentDO recheck = require(paymentId);
        if (!"SUCCESS".equals(recheck.getStatus())) {
            refundMapper.finishWithReason(refund.getId(), "FAILED", "支付单状态已变更（撤销竞态守卫）: " + recheck.getStatus());
            mirror("refund_record", refund.getId());
            throw new BizException(ResultCode.CONFLICT, "支付单状态已变更，不可退款: " + recheck.getStatus());
        }

        // P-4 退款异步化：仅 CARD 单腿 + 异步渠道开关开启时 WAITING（与支付侧 CH-1 同一开关）；
        // 缺省 false / BALANCE / 资金单 / 组合单路径与改造前逐字节一致（tryAll→commitAsync 同步终态）。
        boolean awaiting = channelAsync && "CARD".equals(payment.getMethod()) && !combo;
        String xid = awaiting ? coordinator.beginAwaiting("REFUND", refund.getId()) : coordinator.begin("REFUND", refund.getId());
        tryGrace(xid);
        refundMapper.updateXid(refund.getId(), xid);
        mirror("refund_record", refund.getId());
        log.info("退款受理 refundId={} paymentId={} amount={} feeRefund={} requestId={} xid={} awaiting={}",
                refund.getId(), paymentId, amount, feeRefund, request.getRequestId(), xid, awaiting);
        if (combo) {
            // 每腿独立 accounting REFUND 分支（bizNo=refundId:legSeq）+ CARD 腿 channel REFUND（origBizNo=原腿扣款 bizNo）
            List<PaymentLegDO> legs = legMapper.selectByPaymentId(paymentId);
            int seq = 0;
            for (LegAllocDO alloc : legAllocs) {
                seq++;
                PaymentLegDO leg = legs.stream().filter(l -> l.getId().equals(alloc.getLegId())).findFirst().orElseThrow();
                String legBizNo = refund.getId() + ":" + seq;
                coordinator.registerBranch(xid, "accounting", "REFUND", comboRefundContext(payment, leg, alloc, legBizNo));
                if ("CARD".equals(leg.getMethod())) {
                    ChannelTccContext channelCtx = channelContext(legBizNo, leg.getId(), leg.getCardNo(), alloc.getAmount());
                    coordinator.registerBranch(xid, "channel", "REFUND", channelCtx);
                }
            }
        } else {
            coordinator.registerBranch(xid, "accounting", "REFUND", refundContext(payment, refund));
            if ("CARD".equals(payment.getMethod())) {
                coordinator.registerBranch(xid, "channel", "REFUND",
                        channelContext(refund.getId(), payment.getId(), payment.getCardNo(), amount));
            }
        }
        coordinator.registerBranch(xid, "trade", "REFUND",
                tradeContext(payment.getOrderId(), paymentId, amount, refund.getId()));
        // P2-H T-5：合并支付退款 —— 承载单标准 REFUND 分支（上）+ 各子订单按占比水位填充分摊
        // （Σ子订单分摊 = 退款额，误差 0；各子订单独立 REFUND_MARK bizNo=refundId:orderId 幂等）
        if ("MERGE".equals(payment.getPayMode()) || "MERGE_COMBO".equals(payment.getPayMode())) {
            registerMergeRefundBranches(xid, payment, refund.getId(), amount);
        }
        GlobalStatus preTry = coordinator.status(xid);
        if (preTry == GlobalStatus.CANCELLED || preTry == GlobalStatus.CANCELLING) {
            log.warn("退款受理让位（事务已回滚，防悬挂）xid={} status={}", xid, preTry);
            throw new BizException(ResultCode.CONFLICT, "退款事务已回滚，受理让位: " + refund.getId());
        }
        coordinator.tryAll(xid);
        if (awaiting) {
            log.info("银行卡异步退款已受理，等待渠道终态 refundId={} xid={}", refund.getId(), xid);
        } else {
            coordinator.commitAsync(xid);
        }
        return refundResult(require(paymentId), amount, refund.getId(), "PROCESSING");
    }

    /** P-4 退款单查询（独立终态）。 */
    public RefundDTO getRefund(String refundId) {
        RefundDO refund = readYourWrite(() -> refundMapper.selectById(refundId));
        if (refund == null) {
            throw new BizException(ResultCode.NOT_FOUND, "退款单不存在: " + refundId);
        }
        return RefundReconcileService.toDTO(refund);
    }

    /** P-4 退款补单收敛（手动/回执后即时触发，幂等）。 */
    public RefundDTO refundReconverge(String refundId) {
        return refundReconcileService.reconverge(refundId, true);
    }

    /**
     * P2-H T-5 合并退款子订单分摊（水位填充，确定性幂等）：各子订单按入合并金额占比 floor 分配
     * 且不超过其可退余额（amount−refunded），余量按序补齐；Σ分摊 == 退款额（误差 0）。
     * 每子订单独立 trade REFUND 分支（Try checkRefundable 守卫 / Confirm refundMark bizNo=refundId:orderId）。
     */
    private void registerMergeRefundBranches(String xid, PaymentDO payment, String refundId, long amount) {
        com.qoder.payment.facade.trade.MergeOrderDTO merge = tradeFacade.getMergeOrder(payment.getMergeId()).orThrow();
        List<com.qoder.payment.facade.trade.MergeRelDTO> rels = merge.getRels();
        long total = merge.getTotalAmount();
        long[] remaining = new long[rels.size()];
        long remainingSum = 0;
        for (int i = 0; i < rels.size(); i++) {
            long refunded = rels.get(i).getRefundedAmount() == null ? 0L : rels.get(i).getRefundedAmount();
            remaining[i] = rels.get(i).getAmount() - refunded;
            remainingSum += remaining[i];
        }
        if (amount > remainingSum) {
            throw new BizException(ResultCode.BIZ_ERROR, "合并退款超出子订单可退余额: " + amount + " > " + remainingSum);
        }
        long[] alloc = new long[rels.size()];
        long left = amount;
        for (int i = 0; i < rels.size(); i++) {
            long share = amount * rels.get(i).getAmount() / total;
            alloc[i] = Math.min(remaining[i], share);
            left -= alloc[i];
        }
        for (int i = 0; i < rels.size() && left > 0; i++) {
            long add = Math.min(left, remaining[i] - alloc[i]);
            alloc[i] += add;
            left -= add;
        }
        if (left > 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "合并退款分摊不守恒（余量 " + left + "）");
        }
        for (int i = 0; i < rels.size(); i++) {
            if (alloc[i] <= 0) {
                continue;
            }
            coordinator.registerBranch(xid, "trade", "REFUND",
                    tradeContext(rels.get(i).getOrderId(), payment.getId(), alloc[i], refundId + ":" + rels.get(i).getOrderId()));
        }
    }

    /** P-2 组合退款腿账务上下文：反向分录（商户 D 净额 + SYS_FEE D 费 + 腿付款方/SYS_CASH C 全额）。 */
    private AccountingTccContext comboRefundContext(PaymentDO payment, PaymentLegDO leg, LegAllocDO alloc, String bizNo) {
        AccountingTccContext ctx = new AccountingTccContext();
        ctx.setBizNo(bizNo);
        ctx.setRefId(payment.getId());
        ctx.setSubject("组合退款-" + payment.getId() + "-腿" + leg.getLegSeq());
        ctx.setReversed(true);
        long net = alloc.getAmount() - alloc.getFee();
        List<TransferLineDTO> lines = new ArrayList<>();
        lines.add(line(payment.getMerchantAccountId(), "D", net));
        if (alloc.getFee() > 0) {
            lines.add(line("SYS_FEE", "D", alloc.getFee()));
        }
        if ("BALANCE".equals(leg.getMethod())) {
            lines.add(line(leg.getPayerAccountId(), "C", alloc.getAmount()));
        } else {
            lines.add(line("SYS_CASH", "C", alloc.getAmount()));
        }
        ctx.setLines(lines);
        return ctx;
    }

    public List<PaymentDTO> listPayments() {
        return paymentMapper.selectAll().stream().map(p -> {
            PaymentDTO dto = PaymentConverter.toDTO(p);
            // additive：列表只补 legCount（组合单才查腿数），不带 legs，控制响应体积
            if (PaymentCompletionListener.isComboMode(dto.getPayMode())) {
                dto.setLegCount(legMapper.selectByPaymentId(p.getId()).size());
            } else {
                dto.setLegCount(0);
            }
            return dto;
        }).toList();
    }

    /** additive：退款单分页列表（管理台退款单列表页；paymentId/status 可选过滤）。 */
    public com.qoder.payment.facade.common.PageDTO<com.qoder.payment.facade.payment.RefundDTO> listRefunds(
            String paymentId, String status, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(200, Math.max(1, size));
        long total = refundMapper.countPage(paymentId, status);
        java.util.List<com.qoder.payment.facade.payment.RefundDTO> items =
                refundMapper.selectPage(paymentId, status, (p - 1) * s, s).stream()
                        .map(PaymentConverter::toDTO).toList();
        return com.qoder.payment.facade.common.PageDTO.of(items, p, s, total);
    }

    public PaymentDTO getPayment(String paymentId) {
        PaymentDTO dto = PaymentConverter.toDTO(require(paymentId));
        if (PaymentCompletionListener.isComboMode(dto.getPayMode())) {
            dto.setLegs(legMapper.selectByPaymentId(paymentId).stream().map(ComboPayService::toLegDTO).toList());
        }
        return dto;
    }

    /**
     * 手动补单收敛（S-5/P-1）：运营/商户或渠道回执后即时触发，忽略超时阈值与退避，
     * 仍经 TCC 决策与状态 CAS，重复调用幂等（不重复过账、不重复 outbox）。
     */
    public PaymentDTO reconverge(String paymentId) {
        return reconcileService.reconverge(paymentId, true);
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
        PaymentDO payment = readYourWrite(() -> paymentMapper.selectById(paymentId));
        if (payment == null) {
            throw new BizException(ResultCode.NOT_FOUND, "支付单不存在: " + paymentId);
        }
        return payment;
    }

    // ------------------------------------------------------------------ I-5b 分片支撑

    /**
     * I-5b 取号：分片态走全局发号（雪花，杜绝各片 sequences 重号 E-7）；
     * 单库态（local / 一键回滚后）仍走 {@code next_value_for('seq_payment')}。
     * <b>ID 形态不变</b>：始终 {@code P + 十进制数字}，长度 ≤ 20 &lt; VARCHAR(32)，存量 id 与 ride 双账本兼容。
     */
    private String newPaymentId() {
        ShardRuntime runtime = shardRuntime.getIfAvailable();
        if (runtime != null && runtime.isActive()) {
            return idGenerator.getObject().next("P");
        }
        return "P" + paymentMapper.nextId();
    }

    /** I-5b 退款取号：形态仍为 {@code R + 十进制数字}。 */
    private String newRefundId() {
        ShardRuntime runtime = shardRuntime.getIfAvailable();
        if (runtime != null && runtime.isActive()) {
            return idGenerator.getObject().next("R");
        }
        return "R" + refundMapper.nextId();
    }

    /**
     * I-5b 支付单落库闸门：分片态经单库不分片的幂等索引表占位（跨片全局幂等）；
     * 未装配分片 Bean 时逐字节沿用原路径（uk_payment_request 全局唯一键兜底）。
     */
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

    /** I-5b 退款单落库闸门（同上，refund_request_index 兜底跨片全局退款幂等）。 */
    private RefundDO insertRefund(RefundDO refund) {
        PaymentRequestIndex gate = requestIndex.getIfAvailable();
        if (gate != null) {
            return gate.insertRefund(refund);
        }
        try {
            refundMapper.insert(refund);
            return null;
        } catch (DuplicateKeyException e) {
            return refundMapper.selectByRequestId(refund.getRequestId());
        }
    }

    /** I-5b 全行双写：把刚提交的整行真值同步到影子侧（回滚后逐条一致的保障）。 */
    private void mirror(String table, Object pk) {
        ShardRowMirror mirror = shardRowMirror.getIfAvailable();
        if (mirror != null) {
            mirror.mirror(table, pk);
        }
    }

    /** I-5b 资金读己之写：强制走主库（未装配分片时零开销直接执行）。 */
    private <T> T readYourWrite(Supplier<T> action) {
        ShardRuntime runtime = shardRuntime.getIfAvailable();
        return runtime == null ? action.get() : runtime.readYourWrite(action);
    }

    private static void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "requestId 必填（幂等键）");
        }
    }

    /**
     * P1-I 同步事务一阶段宽限（资金可靠性护栏，不改 tcc-core）：
     * begin 落库即 next_retry_at=now，XXL-Job/本地 recover 每 5s 扫描，可能在本线程
     * registerBranch→tryAll 的在途窗口（~20ms）内把 TRYING 且瞬时 allTryOk=false 的事务
     * 误判「一阶段未完成」回滚（与 CH-1 beginAwaiting 的 TRY_GRACE_MS 同一设计思想）。
     * 受理侧把刚开启的 TRYING 事务下次扫描推迟 10s：正常路径 tryAll→commitAsync 毫秒级完成，
     * 宽限不影响任何成功语义；崩溃路径 recover 最多延迟 10s 续驱（资金冻结不丢）。
     * CAS 守卫：仅 status='TRYING' 可推迟，已被 recover/关闭抢跑的事务不受影响。
     */
    private void tryGrace(String xid) {
        jdbc.update("UPDATE dtx_transaction SET next_retry_at=? WHERE xid=? AND status='TRYING'",
                System.currentTimeMillis() + 10_000L, xid);
    }

    private PayResultDTO result(PaymentDO payment, boolean repeated) {
        PayResultDTO result = new PayResultDTO();
        PaymentDTO dto = PaymentConverter.toDTO(payment);
        if (PaymentCompletionListener.isComboMode(payment.getPayMode())) {
            dto.setLegs(legMapper.selectByPaymentId(payment.getId()).stream().map(ComboPayService::toLegDTO).toList());
        }
        result.setPayment(dto);
        result.setRepeated(repeated);
        return result;
    }

    private static RefundResultDTO refundResult(PaymentDO payment, long amount, String refundId, String refundStatus) {
        RefundResultDTO result = new RefundResultDTO();
        result.setPayment(PaymentConverter.toDTO(payment));
        result.setRefundAmount(amount);
        result.setRefundId(refundId);
        result.setRefundStatus(refundStatus);
        return result;
    }
}