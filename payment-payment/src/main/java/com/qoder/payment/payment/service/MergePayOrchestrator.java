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
import com.qoder.payment.facade.channel.ChannelTccContext;
import com.qoder.payment.facade.payment.MergePayRequest;
import com.qoder.payment.facade.payment.PayLeg;
import com.qoder.payment.facade.payment.PayResultDTO;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.facade.risk.RiskCheckRequest;
import com.qoder.payment.facade.risk.RiskFacade;
import com.qoder.payment.facade.risk.RiskResultDTO;
import com.qoder.payment.facade.trade.MergeOrderDTO;
import com.qoder.payment.facade.trade.MergeRelDTO;
import com.qoder.payment.facade.trade.OrderCreateRequest;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.TradeFacade;
import com.qoder.payment.facade.trade.TradeTccContext;
import com.qoder.payment.payment.convert.PaymentConverter;
import com.qoder.payment.payment.dataobject.PaymentDO;
import com.qoder.payment.payment.dataobject.PaymentLegDO;
import com.qoder.payment.payment.mapper.PaymentLegMapper;
import com.qoder.payment.payment.mapper.PaymentMapper;
import com.qoder.tcc.GlobalStatus;
import com.qoder.tcc.TccCoordinator;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * T-5 合并支付编排（payment 侧）：多订单 → 一笔支付单（payMode=MERGE / MERGE_COMBO）。
 *
 * <p><b>结构</b>：合并单（trade.merge_order）+ 承载单（trade_order bizType=MERGE，amount=合并额）。
 * 支付单 order_id 指向承载单 ⇒ recon「支付-交易」/settle/notify 既有口径逐字节不变；
 * 各子订单经独立 trade MERGE_PAY 分支联动（Try 预留 / Confirm PAID+计数 / Cancel 释放）。
 *
 * <p><b>两层守恒（与 COMBO 叠加）</b>：
 * <ul>
 *   <li>trade 维度：Σ子订单额 = 合并额（入合并快照 + 受理复核，误差 0）；</li>
 *   <li>资金维度：Σ支付腿 = 合并额（COMBO 叠加时强校验）；每腿独立 accounting 分支 bizNo=legId；</li>
 *   <li>fee：按合并总额受理快照（feeFor 口径不变），腿费占比分摊末腿吸收余差（Σ腿fee=总fee），
 *       子订单 rel.fee 同法分摊（Σrel.fee=总fee，信息性快照，账务凭证仍 1:1 支付单）。</li>
 * </ul>
 *
 * <p><b>部分失败整体回滚</b>：单全局事务，任一子订单/腿 Try 失败 → tryAll 整体回滚
 * （各腿解冻、子订单释放、承载单释放、合并单 PAYING→CREATED），支付单 FAILED（完结监听），
 * 合并单可重付（新 requestId）或经 dissolveMerge 拆分个别支付。
 *
 * <p><b>幂等</b>：payment.request_id UNIQUE（I-5b 索引闸门）+ 合并单受理 CAS +
 * MERGE_PAY 分支 bizNo=paymentId:orderId 模板幂等。BALANCE 同步受理→PROCESSING→SUCCESS。
 */
@Slf4j
@Service
public class MergePayOrchestrator {

    private final PaymentMapper paymentMapper;
    private final PaymentLegMapper legMapper;
    private final TccCoordinator coordinator;
    private final FeeGateway feeGateway;
    private final PaymentRouteService routeService;
    private final PaymentReconcileService reconcileService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
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

    @Value("${payment.channel.async:false}")
    private boolean channelAsync;

    public MergePayOrchestrator(PaymentMapper paymentMapper, PaymentLegMapper legMapper, TccCoordinator coordinator,
                                FeeGateway feeGateway, PaymentRouteService routeService,
                                PaymentReconcileService reconcileService, JdbcTemplate jdbc,
                                PlatformTransactionManager txManager,
                                ObjectProvider<ShardRuntime> shardRuntime, ObjectProvider<IdGenerator> idGenerator,
                                ObjectProvider<ShardRowMirror> shardRowMirror, ObjectProvider<PaymentRequestIndex> requestIndex) {
        this.paymentMapper = paymentMapper;
        this.legMapper = legMapper;
        this.coordinator = coordinator;
        this.feeGateway = feeGateway;
        this.routeService = routeService;
        this.reconcileService = reconcileService;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.shardRuntime = shardRuntime;
        this.idGenerator = idGenerator;
        this.shardRowMirror = shardRowMirror;
        this.requestIndex = requestIndex;
    }

    public PayResultDTO payMerge(MergePayRequest request) {
        if (request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "requestId 必填（幂等键）");
        }
        if (request.getMergeOrderId() == null || request.getMergeOrderId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "mergeOrderId 必填");
        }
        PaymentDO replay = readYourWrite(() -> paymentMapper.selectByRequestId(request.getRequestId()));
        if (replay != null) {
            log.info("合并支付幂等命中 requestId={} paymentId={}", request.getRequestId(), replay.getId());
            return result(replay, true);
        }
        MergeOrderDTO merge = tradeFacade.getMergeOrder(request.getMergeOrderId()).orThrow();
        // 在途/终态续驱（崩溃窗口幂等收敛）：PAYING 且支付单在途 → 补单收敛返回；已终态失败 → 重开续付
        if ("PAID".equals(merge.getStatus())) {
            if (merge.getPaymentId() == null) {
                throw new BizException(ResultCode.CONFLICT, "合并单已支付但无支付单关联: " + merge.getId());
            }
            return result(require(merge.getPaymentId()), true);
        }
        if ("PAYING".equals(merge.getStatus())) {
            final String payingPaymentId = merge.getPaymentId();
            PaymentDO existing = payingPaymentId == null ? null
                    : readYourWrite(() -> paymentMapper.selectById(payingPaymentId));
            if (existing != null && "PROCESSING".equals(existing.getStatus())) {
                reconcileService.reconverge(existing.getId(), true);
                return result(require(existing.getId()), true);
            }
            if (existing != null && "SUCCESS".equals(existing.getStatus())) {
                return result(existing, true);
            }
            merge = tradeFacade.reopenMerge(merge.getId()).orThrow();
        }
        if (!"CREATED".equals(merge.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "合并单状态不可支付: " + merge.getStatus());
        }
        List<MergeRelDTO> rels = merge.getRels();
        if (rels == null || rels.size() < 2) {
            throw new BizException(ResultCode.BIZ_ERROR, "合并单子订单明细缺失/不足两笔");
        }
        long total = merge.getTotalAmount();
        long sum = 0;
        for (MergeRelDTO rel : rels) {
            if (!"CREATED".equals(rel.getOrderStatus())) {
                throw new BizException(ResultCode.CONFLICT, "子订单状态已变更: " + rel.getOrderId() + " " + rel.getOrderStatus());
            }
            sum += rel.getAmount();
        }
        if (sum != total) {
            throw new BizException(ResultCode.BIZ_ERROR, "合并金额不守恒: Σ子订单=" + sum + " ≠ 合并额=" + total);
        }
        MoneyUtil.requireValid(total);
        AccountDTO merchantAccount = accountFacade.getByMember(merge.getMerchantId()).orThrow();

        boolean combo = request.getLegs() != null && !request.getLegs().isEmpty();
        List<String> legChannels = new ArrayList<>();
        if (combo) {
            validateLegs(request.getLegs(), total, legChannels);
        } else {
            if (request.getAccountId() == null || request.getAccountId().isBlank()) {
                throw new BizException(ResultCode.PARAM_ERROR, "合并支付必须指定 accountId（BALANCE）");
            }
            AccountDTO payer = accountFacade.get(request.getAccountId()).orThrow();
            if (!"ACTIVE".equals(payer.getStatus())) {
                throw new BizException(ResultCode.BIZ_ERROR, "付款账户已冻结: " + payer.getId());
            }
            if (payer.getBalance() < total) {
                throw new BizException(ResultCode.BIZ_ERROR, "账户余额不足: " + payer.getId());
            }
        }
        long fee = feeGateway.feeFor(merge.getMerchantId(), "TRADE", combo ? "COMBO" : "BALANCE", total);
        // 子订单 fee 分摊快照（占比 floor + 末行吸收余差，Σrel.fee = 总 fee）
        List<Long> relFees = allocate(rels.stream().map(MergeRelDTO::getAmount).toList(), total, fee);
        long[] legFees = combo ? allocateArray(request.getLegs().stream().map(PayLeg::getAmount).toList(), total, fee) : null;

        // 承载单（trade_order bizType=MERGE）：支付单 order_id 指向它，recon/settle/notify 口径不变
        String carrierId = merge.getCarrierOrderId();
        if (carrierId != null) {
            OrderDTO carrier = tradeFacade.getOrder(carrierId).orThrow();
            if (!"CREATED".equals(carrier.getStatus())) {
                carrierId = null; // 旧承载单已关闭（重开/撤销路径）→ 建新
            }
        }
        if (carrierId == null) {
            OrderCreateRequest oc = new OrderCreateRequest();
            oc.setMerchantId(merge.getMerchantId());
            oc.setSubject("合并支付-" + merge.getId());
            oc.setAmount(total);
            oc.setBizType("MERGE");
            oc.setProductType("INSTANT");
            oc.setExpireMinutes(60);
            carrierId = tradeFacade.createOrder(oc).orThrow().getId();
        }
        String paymentId = newPaymentId();
        try {
            tradeFacade.prepareMergePay(merge.getId(), paymentId, carrierId, relFees).orThrow();
        } catch (RuntimeException e) {
            closeCarrierQuietly(carrierId);
            throw e;
        }

        PaymentDO payment = new PaymentDO();
        payment.setId(paymentId);
        payment.setOrderId(carrierId);
        payment.setMerchantId(merge.getMerchantId());
        payment.setBizType("TRADE");
        payment.setMethod(combo ? "COMBO" : "BALANCE");
        payment.setPayMode(combo ? "MERGE_COMBO" : "MERGE");
        payment.setMergeId(merge.getId());
        payment.setPayerAccountId(combo
                ? request.getLegs().stream().filter(l -> "BALANCE".equals(l.getMethod())).map(PayLeg::getAccountId).findFirst().orElse(null)
                : request.getAccountId());
        payment.setCardNo(combo
                ? request.getLegs().stream().filter(l -> "CARD".equals(l.getMethod())).map(PayLeg::getCardNo).findFirst().orElse(null)
                : null);
        payment.setMerchantAccountId(merchantAccount.getId());
        payment.setAmount(total);
        payment.setFee(fee);
        payment.setStatus("PROCESSING");
        payment.setRefundedAmount(0L);
        payment.setRefundedFee(0L);
        payment.setChannelCode(legChannels.stream().filter(c -> c != null).findFirst().orElse(null));
        payment.setRequestId(request.getRequestId());
        payment.setCallbackUrl(request.getCallbackUrl());
        payment.setCreatedAt(System.currentTimeMillis());

        List<PaymentLegDO> legRows = new ArrayList<>();
        if (combo) {
            for (int i = 0; i < request.getLegs().size(); i++) {
                PayLeg leg = request.getLegs().get(i);
                PaymentLegDO row = new PaymentLegDO();
                row.setId("PL" + legMapper.nextId());
                row.setPaymentId(paymentId);
                row.setLegSeq(i + 1);
                row.setMethod(leg.getMethod());
                row.setPayerAccountId(leg.getAccountId());
                row.setCardNo(leg.getCardNo());
                row.setAmount(leg.getAmount());
                row.setFee(legFees[i]);
                row.setStatus("PENDING");
                row.setChannelBizNo("CARD".equals(leg.getMethod()) ? row.getId() : null);
                row.setChannelCode(legChannels.get(i));
                row.setRefundedAmount(0L);
                row.setRefundedFee(0L);
                row.setCreatedAt(payment.getCreatedAt());
                legRows.add(row);
            }
        }
        final String carrierFinal = carrierId;
        // 失败守卫必须覆盖「prepareMergePay 置 PAYING 之后、TCC begin 之前」的整段：
        // 原 try 从 coordinator.begin 才开始，风控降级(B0003/503)或落单库异常会绕过 reopenMerge，
        // 合并单永久停在 PAYING（钱分文未动却悬挂），违反零悬挂要求。
        String xid = null;
        try {
        PaymentDO replayed = tx.execute(s -> {
            PaymentDO r = insertPayment(payment);
            if (r != null) {
                return r;
            }
            for (PaymentLegDO row : legRows) {
                legMapper.insert(row);
            }
            return null;
        });
        if (replayed != null) {
            // 极端并发：同 requestId 已落单 → 让位并释放本次占位
            tradeFacade.reopenMerge(merge.getId());
            return result(replayed, true);
        }

        // 风控（合并总额一次校验；业务失败留 FAILED 痕 + 合并单重开，与单腿口径一致）
        RiskCheckRequest risk = new RiskCheckRequest();
        risk.setPayerId(combo ? ChallengeService.payerKey(request.getLegs().get(0).getMethod(),
                request.getLegs().get(0).getAccountId(), request.getLegs().get(0).getCardNo()) : request.getAccountId());
        risk.setMerchantId(merge.getMerchantId());
        risk.setAmount(total);
        risk.setMethod(combo ? "COMBO" : "BALANCE");
        RiskResultDTO riskResult = riskFacade.check(risk).orThrow();
        if (!Boolean.TRUE.equals(riskResult.getPass())) {
            // FAILED 留痕 / 腿终态 / 合并重开 / 承载单关闭统一由下方 xid==null 守卫处理，避免双重清理
            MetricsSupport.payFail(payment.getMethod(), payment.getBizType());
            log.warn("合并支付风控拒绝 paymentId={} reason={}", paymentId, riskResult.getReason());
            throw new BizException(ResultCode.BIZ_ERROR, "风控拒绝: " + riskResult.getReason());
        }

            xid = coordinator.begin("PAY", paymentId);
            // 一阶段宽限（与 PaymentService.tryGrace 同一护栏）
            jdbc.update("UPDATE dtx_transaction SET next_retry_at=? WHERE xid=? AND status='TRYING'",
                    System.currentTimeMillis() + 10_000L, xid);
            paymentMapper.updateXid(paymentId, xid);
            mirror(paymentId);
            log.info("合并支付受理 paymentId={} mergeId={} carrier={} total={} fee={} combo={} requestId={} xid={}",
                    paymentId, merge.getId(), carrierFinal, total, fee, combo, request.getRequestId(), xid);
            if (combo) {
                for (PaymentLegDO row : legRows) {
                    coordinator.registerBranch(xid, "accounting", "PAY", legAccountingContext(row, carrierFinal, merchantAccount.getId()));
                    if ("CARD".equals(row.getMethod())) {
                        ChannelTccContext channel = new ChannelTccContext();
                        channel.setBizNo(row.getId());
                        channel.setCardNo(row.getCardNo());
                        channel.setAmount(row.getAmount());
                        channel.setChannelCode(row.getChannelCode());
                        coordinator.registerBranch(xid, "channel", "PAY", channel);
                    }
                }
            } else {
                coordinator.registerBranch(xid, "accounting", "PAY", singleAccountingContext(payment));
            }
            // 承载单标准 PAY 分支（recon/settle 口径锚点）
            coordinator.registerBranch(xid, "trade", "PAY", tradeContext(carrierFinal, paymentId, total, paymentId));
            // 各子订单 MERGE_PAY 分支（一子订单 Try 失败 → 整体回滚）
            for (MergeRelDTO rel : rels) {
                TradeTccContext sub = tradeContext(rel.getOrderId(), paymentId, rel.getAmount(), paymentId + ":" + rel.getOrderId());
                sub.setMergeId(merge.getId());
                coordinator.registerBranch(xid, "trade", "MERGE_PAY", sub);
            }
            GlobalStatus preTry = coordinator.status(xid);
            if (preTry == GlobalStatus.CANCELLED || preTry == GlobalStatus.CANCELLING) {
                log.warn("合并受理让位（事务已回滚/关闭，防悬挂）xid={} status={}", xid, preTry);
                throw new BizException(ResultCode.CONFLICT, "合并支付已关闭/回滚，受理让位: " + paymentId);
            }
            coordinator.tryAll(xid);
            coordinator.commitAsync(xid);
            return result(require(paymentId), false);
        } catch (RuntimeException e) {
            if (xid == null) {
                // begin 之前失败：资金从未预留 → FAILED 留痕 + 腿终态 + 承载单关闭 + 合并重开（零悬挂）
                paymentMapper.finish(paymentId, "FAILED", shortReason(e.getMessage()));
                if (combo) {
                    legMapper.finishByPaymentId(paymentId, "FAILED");
                }
                mirror(paymentId);
                tradeFacade.reopenMerge(merge.getId());
                closeCarrierQuietly(carrierFinal);
                log.warn("合并支付受理前失败，合并单重开 paymentId={} mergeId={}: {}",
                        paymentId, merge.getId(), shortReason(e.getMessage()));
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ 内部

    private void validateLegs(List<PayLeg> legs, long total, List<String> legChannels) {
        if (legs.size() < 2) {
            throw new BizException(ResultCode.PARAM_ERROR, "组合支付至少两腿");
        }
        long sum = 0;
        boolean anyCard = false;
        for (PayLeg leg : legs) {
            if (leg == null || leg.getAmount() == null) {
                throw new BizException(ResultCode.PARAM_ERROR, "组合腿金额必填");
            }
            MoneyUtil.requireValid(leg.getAmount());
            if (!"BALANCE".equals(leg.getMethod()) && !"CARD".equals(leg.getMethod())) {
                throw new BizException(ResultCode.PARAM_ERROR, "组合腿 method 仅支持 BALANCE 或 CARD");
            }
            sum += leg.getAmount();
            anyCard |= "CARD".equals(leg.getMethod());
        }
        if (sum != total) {
            throw new BizException(ResultCode.PARAM_ERROR, "合并组合支付金额不守恒: Σ腿=" + sum + " ≠ 合并额=" + total);
        }
        if (anyCard && channelAsync) {
            throw new BizException(ResultCode.BIZ_ERROR, "异步渠道模式下合并组合支付暂不支持 CARD 腿");
        }
        for (PayLeg leg : legs) {
            if ("BALANCE".equals(leg.getMethod())) {
                if (leg.getAccountId() == null || leg.getAccountId().isBlank()) {
                    throw new BizException(ResultCode.PARAM_ERROR, "余额腿必须指定 accountId");
                }
                AccountDTO payer = accountFacade.get(leg.getAccountId()).orThrow();
                if (!"ACTIVE".equals(payer.getStatus())) {
                    throw new BizException(ResultCode.BIZ_ERROR, "付款账户已冻结: " + payer.getId());
                }
                if (payer.getBalance() < leg.getAmount()) {
                    throw new BizException(ResultCode.BIZ_ERROR, "账户余额不足: " + payer.getId());
                }
                legChannels.add(null);
            } else {
                if (leg.getCardNo() == null || leg.getCardNo().isBlank()) {
                    throw new BizException(ResultCode.PARAM_ERROR, "银行卡腿必须指定 cardNo");
                }
                legChannels.add(routeService.routeForPay(leg.getAmount(), leg.getCardNo()));
            }
        }
    }

    /** 占比 floor 分摊 + 末行吸收余差（Σ分摊 = 总额，误差 0）。 */
    static List<Long> allocate(List<Long> weights, long total, long target) {
        long[] arr = allocateArray(weights, total, target);
        List<Long> out = new ArrayList<>(arr.length);
        for (long v : arr) {
            out.add(v);
        }
        return out;
    }

    static long[] allocateArray(List<Long> weights, long total, long target) {
        long[] alloc = new long[weights.size()];
        long allocated = 0;
        for (int i = 0; i < weights.size(); i++) {
            alloc[i] = i == weights.size() - 1 ? target - allocated : target * weights.get(i) / total;
            allocated += alloc[i];
        }
        return alloc;
    }

    private AccountingTccContext singleAccountingContext(PaymentDO payment) {
        AccountingTccContext ctx = new AccountingTccContext();
        ctx.setBizNo(payment.getId());
        ctx.setRefId(payment.getOrderId());
        ctx.setSubject("合并支付-" + payment.getMergeId());
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

    private AccountingTccContext legAccountingContext(PaymentLegDO leg, String carrierId, String merchantAccountId) {
        AccountingTccContext ctx = new AccountingTccContext();
        ctx.setBizNo(leg.getId());
        ctx.setRefId(carrierId);
        ctx.setSubject("合并组合支付-腿" + leg.getLegSeq());
        ctx.setReversed(false);
        long net = leg.getAmount() - leg.getFee();
        List<TransferLineDTO> lines = new ArrayList<>();
        if ("BALANCE".equals(leg.getMethod())) {
            lines.add(line(leg.getPayerAccountId(), "D", leg.getAmount()));
        } else {
            lines.add(line("SYS_CASH", "D", leg.getAmount()));
        }
        lines.add(line(merchantAccountId, "C", net));
        if (leg.getFee() > 0) {
            lines.add(line("SYS_FEE", "C", leg.getFee()));
        }
        ctx.setLines(lines);
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

    private void closeCarrierQuietly(String carrierId) {
        try {
            tradeFacade.closeOrder(carrierId);
        } catch (RuntimeException e) {
            log.warn("承载单关闭让位（交由超时关闭作业兜底）carrier={} {}", carrierId, e.getMessage());
        }
    }

    private static String shortReason(String message) {
        if (message == null) {
            return "合并受理失败";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
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
        if (PaymentCompletionListener.isComboMode(payment.getPayMode())) {
            dto.setLegs(legMapper.selectByPaymentId(payment.getId()).stream().map(ComboPayService::toLegDTO).toList());
        }
        result.setPayment(dto);
        result.setRepeated(repeated);
        return result;
    }
}