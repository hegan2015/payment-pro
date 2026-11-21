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
import com.qoder.payment.facade.payment.PayLeg;
import com.qoder.payment.facade.payment.PayRequest;
import com.qoder.payment.facade.marketing.MarketingTccContext;
import com.qoder.payment.facade.payment.PayResultDTO;
import com.qoder.payment.facade.payment.PaymentLegDTO;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.facade.risk.RiskCheckRequest;
import com.qoder.payment.facade.risk.RiskFacade;
import com.qoder.payment.facade.risk.RiskResultDTO;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.TradeTccContext;
import com.qoder.payment.payment.convert.PaymentConverter;
import com.qoder.payment.payment.dataobject.LegAllocDO;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * P-2 组合支付编排：余额+银行卡多腿组合，<b>单全局事务 + 每腿独立 TCC 分支 + 整体原子</b>。
 *
 * <p>资金护航：
 * <ul>
 *   <li>金额守恒：Σlegs.amount == order.amount（受理前强校验，不等拒绝，不落单）；</li>
 *   <li>手续费按订单总额计（受理快照），各腿按金额占比分摊、末腿吸收余差，Σ腿fee == 总fee；</li>
 *   <li>每腿一个 accounting 分支（bizNo=legId，freeze 各腿付款方）+ CARD 腿 channel 分支
 *       （bizNo=legId，P-6 路由择优透传）+ trade 分支一次 reserve（bizNo=paymentId）；</li>
 *   <li>一腿 Try 失败 → TCC tryAll 整体回滚（各腿解冻/空回滚），支付单 FAILED，不留悬挂；</li>
 *   <li>幂等键不变：主单 payment.request_id UNIQUE（I-5b 索引闸门）+ 各腿 bizNo=legId 分支幂等；</li>
 *   <li>组合单恒走同步 TCC（tryAll→commitAsync）：异步渠道开关开启时 CARD 腿拒绝受理
 *       （多腿异步终态收敛属后续增量，保守不接入），BALANCE 多腿不受影响。</li>
 * </ul>
 */
@Slf4j
@Service
public class ComboPayService {

    private final PaymentMapper paymentMapper;
    private final PaymentLegMapper legMapper;
    private final TccCoordinator coordinator;
    private final ChallengeService challengeService;
    private final PaymentRouteService routeService;
    private final ObjectProvider<ShardRuntime> shardRuntime;
    private final ObjectProvider<IdGenerator> idGenerator;
    private final ObjectProvider<ShardRowMirror> shardRowMirror;
    private final ObjectProvider<PaymentRequestIndex> requestIndex;
    private final TransactionTemplate tx;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    /** P2-E P-7 营销抵扣编排（无营销诉求时纯内存短路，组合支付行为逐字节不变）。 */
    private final MarketingPayService marketingPayService;

    @DubboReference
    private AccountFacade accountFacade;

    @DubboReference
    private RiskFacade riskFacade;

    @Value("${payment.channel.async:false}")
    private boolean channelAsync;

    public ComboPayService(PaymentMapper paymentMapper, PaymentLegMapper legMapper, TccCoordinator coordinator,
                           ChallengeService challengeService, PaymentRouteService routeService,
                           ObjectProvider<ShardRuntime> shardRuntime, ObjectProvider<IdGenerator> idGenerator,
                           ObjectProvider<ShardRowMirror> shardRowMirror, ObjectProvider<PaymentRequestIndex> requestIndex,
                           PlatformTransactionManager txManager, org.springframework.jdbc.core.JdbcTemplate jdbc,
                           MarketingPayService marketingPayService) {
        this.paymentMapper = paymentMapper;
        this.legMapper = legMapper;
        this.coordinator = coordinator;
        this.challengeService = challengeService;
        this.routeService = routeService;
        this.shardRuntime = shardRuntime;
        this.idGenerator = idGenerator;
        this.shardRowMirror = shardRowMirror;
        this.requestIndex = requestIndex;
        this.tx = new TransactionTemplate(txManager);
        this.jdbc = jdbc;
        this.marketingPayService = marketingPayService;
    }

    public PayResultDTO payCombo(PayRequest request, OrderDTO order, AccountDTO merchantAccount, long fee) {
        List<PayLeg> legs = request.getLegs();
        if (legs == null || legs.size() < 2) {
            throw new BizException(ResultCode.PARAM_ERROR, "组合支付至少两腿（缺省单腿请走原路径）");
        }
        long total = order.getAmount();
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
            throw new BizException(ResultCode.PARAM_ERROR,
                    "组合支付金额不守恒: Σ腿=" + sum + " ≠ 订单额=" + total);
        }
        if (anyCard && channelAsync) {
            throw new BizException(ResultCode.BIZ_ERROR, "异步渠道模式下组合支付暂不支持 CARD 腿，请用单腿支付");
        }
        // P2-E P-7：营销诉求判定纯内存（零 RPC）；无诉求时 marketingAccount=null，下方营销分支全跳过。
        // 组合守恒不变：Σ资金腿 == 订单额；补贴入某条 BALANCE 腿付款户 ⇒ 用户净实付 = 订单额 − Σ抵扣。
        boolean marketing = marketingPayService.requested(request);
        String marketingAccount = null;
        if (marketing) {
            marketingPayService.requireEnabled();
            List<String> balanceAccounts = legs.stream().filter(l -> "BALANCE".equals(l.getMethod()))
                    .map(PayLeg::getAccountId).toList();
            if (balanceAccounts.isEmpty()) {
                throw new BizException(ResultCode.PARAM_ERROR, "组合支付含营销抵扣需至少一条 BALANCE 腿承接补贴");
            }
            String declared = request.getMarketing().getAccountId();
            marketingAccount = declared != null && !declared.isBlank() ? declared : balanceAccounts.get(0);
            if (!balanceAccounts.contains(marketingAccount)) {
                throw new BizException(ResultCode.PARAM_ERROR,
                        "营销抵扣入账户必须是某条 BALANCE 腿付款户: " + marketingAccount);
            }
        }
        // 各腿受理前校验 + CARD 腿路由择优（P-6：失败关闭，不落单不悬挂）
        List<String> legChannels = new ArrayList<>();
        for (PayLeg leg : legs) {
            if ("BALANCE".equals(leg.getMethod())) {
                if (leg.getAccountId() == null || leg.getAccountId().isBlank()) {
                    throw new BizException(ResultCode.PARAM_ERROR, "余额腿必须指定 accountId");
                }
                AccountDTO payer = accountFacade.get(leg.getAccountId()).orThrow();
                if (!"ACTIVE".equals(payer.getStatus())) {
                    throw new BizException(ResultCode.BIZ_ERROR, "付款账户已冻结: " + payer.getId());
                }
                if (payer.getBalance() < leg.getAmount()
                        && !(marketing && leg.getAccountId().equals(marketingAccount))) {
                    // P-7：承接补贴的那条 BALANCE 腿，余额充足性延后到补贴到账后校验（其余腿不变）
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

        // 手续费分摊：按腿金额占比（floor），末腿吸收余差 → Σ腿fee == 总fee（守恒）
        long[] legFees = new long[legs.size()];
        long allocatedFee = 0;
        for (int i = 0; i < legs.size(); i++) {
            legFees[i] = i == legs.size() - 1 ? fee - allocatedFee : fee * legs.get(i).getAmount() / total;
            allocatedFee += legFees[i];
        }

        PaymentDO payment = new PaymentDO();
        payment.setId(newPaymentId());
        payment.setOrderId(order.getId());
        payment.setMerchantId(order.getMerchantId());
        payment.setBizType("TRADE");
        payment.setMethod("COMBO");
        payment.setPayMode("COMBO");
        payment.setPayerAccountId(legs.stream().filter(l -> "BALANCE".equals(l.getMethod()))
                .map(PayLeg::getAccountId).findFirst().orElse(null));
        payment.setCardNo(legs.stream().filter(l -> "CARD".equals(l.getMethod()))
                .map(PayLeg::getCardNo).findFirst().orElse(null));
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
        for (int i = 0; i < legs.size(); i++) {
            PayLeg leg = legs.get(i);
            PaymentLegDO row = new PaymentLegDO();
            row.setId("PL" + legMapper.nextId());
            row.setPaymentId(payment.getId());
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
        // 主单 + 各腿同一本地事务落库（I-5b 索引闸门事务加入外层，跨片全局幂等不变）
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
            log.info("组合支付幂等命中 requestId={} paymentId={}", request.getRequestId(), replayed.getId());
            return result(replayed, true);
        }

        // ==================== P-7 支付宝式：营销纳入 TCC 事务边界（marketing=false 时整段跳过，零影响） ====================
        // 正确模型：营销 Try 只本地预占权益，补贴直补商户在 Confirm 落；marketingAccount 腿 accounting 冻结净实付
        // （腿额−Σ抵扣）。begin 前询价 Σ抵扣。崩溃恢复协调器统一驱动营销 Cancel（本地回滚干净）。
        MarketingTccContext mktCtx = null;
        long mktDeduction = 0L;
        if (marketing) {
            try {
                mktCtx = marketingPayService.buildContext(request, order, payment.getId(),
                        marketingAccount, merchantAccount.getId());
                mktDeduction = marketingPayService.quoteTotal(mktCtx);
            } catch (RuntimeException e) {
                paymentMapper.finish(payment.getId(), "FAILED", MarketingPayService.shortReason("营销抵扣编排失败: " + e.getMessage()));
                legMapper.finishByPaymentId(payment.getId(), "FAILED");
                mirror(payment.getId());
                MetricsSupport.payFail("COMBO", payment.getBizType());
                throw e;
            }
        }

        // 风控（订单总额一次校验，付款人取首腿；CHALLENGE 续付授权与单腿同一机制）
        PayLeg first = legs.get(0);
        String payerKey = ChallengeService.payerKey(first.getMethod(), first.getAccountId(), first.getCardNo());
        RiskCheckRequest risk = new RiskCheckRequest();
        risk.setPayerId(payerKey);
        risk.setMerchantId(order.getMerchantId());
        risk.setAmount(total);
        risk.setMethod("COMBO");
        RiskResultDTO riskResult = riskFacade.check(risk).orThrow();
        if (!Boolean.TRUE.equals(riskResult.getPass())) {
            boolean challengeContinue = "CHALLENGE".equals(riskResult.getAction()) && challengeService.enabled()
                    && challengeService.tryConsumeGrant(request.getRequestId(), order.getId(), payerKey, total);
            if (!challengeContinue) {
                if ("CHALLENGE".equals(riskResult.getAction()) && challengeService.enabled()) {
                    challengeService.recordChallenge(request.getRequestId(), order.getId(), payerKey, total, "COMBO");
                }
                paymentMapper.finish(payment.getId(), "FAILED", riskResult.getReason());
                legMapper.finishByPaymentId(payment.getId(), "FAILED");
                mirror(payment.getId());
                MetricsSupport.payFail("COMBO", payment.getBizType());
                // P-7 TCC：风控拒绝在 begin 之前，营销 Try 尚未执行（无预占/无补贴），无需回退。
                throw new BizException(ResultCode.BIZ_ERROR, "风控拒绝: " + riskResult.getReason());
            }
        }

        try {
            String xid = coordinator.begin("PAY", payment.getId());
            // 一阶段宽限（与 PaymentService.tryGrace 同一护栏）：防 recover 与在途 tryAll 竞态误回滚
            jdbc.update("UPDATE dtx_transaction SET next_retry_at=? WHERE xid=? AND status='TRYING'",
                    System.currentTimeMillis() + 10_000L, xid);
            paymentMapper.updateXid(payment.getId(), xid);
            mirror(payment.getId());
            log.info("组合支付受理 paymentId={} orderId={} total={} fee={} legs={} requestId={} xid={}",
                    payment.getId(), order.getId(), total, fee, legs.size(), request.getRequestId(), xid);
            // P-7：营销分支注册在各腿 accounting 之前 ⇒ tryAll 先驱动营销 Try（本地预占权益）；
            // marketingAccount 腿 accounting 冻结净实付（腿额−Σ抵扣，直补商户模型）；失败协调器统一 Cancel。
            if (mktCtx != null) {
                coordinator.registerBranch(xid, "marketing", "PAY", mktCtx);
            }
            for (PaymentLegDO row : legRows) {
                long legDeduction = (mktCtx != null && marketingAccount != null
                        && marketingAccount.equals(row.getPayerAccountId())) ? mktDeduction : 0L;
                coordinator.registerBranch(xid, "accounting", "PAY",
                        legAccountingContext(row, order, merchantAccount.getId(), legDeduction));
                if ("CARD".equals(row.getMethod())) {
                    ChannelTccContext channel = new ChannelTccContext();
                    channel.setBizNo(row.getId());
                    channel.setCardNo(row.getCardNo());
                    channel.setAmount(row.getAmount());
                    channel.setChannelCode(row.getChannelCode());
                    coordinator.registerBranch(xid, "channel", "PAY", channel);
                }
            }
            TradeTccContext trade = new TradeTccContext();
            trade.setOrderId(order.getId());
            trade.setPaymentId(payment.getId());
            trade.setAmount(total);
            trade.setBizNo(payment.getId());
            coordinator.registerBranch(xid, "trade", "PAY", trade);
            // P-5 防悬挂守卫（与 PaymentService 同一护栏）：零分支抢跑回滚后绝不再 Try
            GlobalStatus preTry = coordinator.status(xid);
            if (preTry == GlobalStatus.CANCELLED || preTry == GlobalStatus.CANCELLING) {
                log.warn("组合受理让位（事务已回滚/关闭，防悬挂）xid={} status={}", xid, preTry);
                throw new BizException(ResultCode.CONFLICT, "支付已关闭/回滚，受理让位: " + payment.getId());
            }
            // 一腿 Try 失败 → tryAll 整体回滚（各腿解冻/空回滚 + 营销 Cancel 释放），支付单 FAILED（完结监听）
            coordinator.tryAll(xid);
            if (mktCtx != null) {
                marketingPayService.recordApplied(payment.getId(), mktCtx, request.getMarketing(), order);
            }
            coordinator.commitAsync(xid);
            return result(require(payment.getId()), false);
        } catch (RuntimeException e) {
            // P-7 TCC：营销预占/预补贴在 Try 阶段，失败由协调器 driveCancel 驱动营销 Cancel 释放+反向补贴；
            // 受理前失败营销 Try 未执行无需回退。无营销诉求时组合支付行为与改造前逐字节一致。
            throw e;
        }
    }

    /**
     * 组合退款分摊（水位填充，确定性幂等）：各腿按金额占比 floor 分配且不超过腿可退余额，
     * 余量按腿序补齐；Σ分配 == 退款额（守恒），腿级不超退。手续费同法对腿费余额分摊。
     */
    public List<LegAllocDO> allocateRefund(List<PaymentLegDO> legs, long refundAmount, long feeRefund) {
        long[] amounts = waterFill(legs, refundAmount, true);
        long[] fees = waterFill(legs, feeRefund, false);
        List<LegAllocDO> allocs = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            if (amounts[i] <= 0 && fees[i] <= 0) {
                continue;
            }
            LegAllocDO alloc = new LegAllocDO();
            alloc.setLegId(legs.get(i).getId());
            alloc.setAmount(amounts[i]);
            alloc.setFee(fees[i]);
            allocs.add(alloc);
        }
        return allocs;
    }

    private long[] waterFill(List<PaymentLegDO> legs, long target, boolean principal) {
        long[] alloc = new long[legs.size()];
        if (target <= 0) {
            return alloc;
        }
        long total = legs.stream().mapToLong(PaymentLegDO::getAmount).sum();
        long[] remaining = new long[legs.size()];
        long remainingSum = 0;
        for (int i = 0; i < legs.size(); i++) {
            PaymentLegDO leg = legs.get(i);
            remaining[i] = principal
                    ? leg.getAmount() - nz(leg.getRefundedAmount())
                    : leg.getFee() - nz(leg.getRefundedFee());
            remainingSum += remaining[i];
        }
        if (target > remainingSum) {
            throw new BizException(ResultCode.BIZ_ERROR, "组合退款超出腿可退余额: " + target + " > " + remainingSum);
        }
        long left = target;
        for (int i = 0; i < legs.size(); i++) {
            long share = target * legs.get(i).getAmount() / total;
            alloc[i] = Math.min(remaining[i], share);
            left -= alloc[i];
        }
        for (int i = 0; i < legs.size() && left > 0; i++) {
            long add = Math.min(left, remaining[i] - alloc[i]);
            alloc[i] += add;
            left -= add;
        }
        if (left > 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "组合退款分摊不守恒（余量 " + left + "）");
        }
        return alloc;
    }

    private AccountingTccContext legAccountingContext(PaymentLegDO leg, OrderDTO order, String merchantAccountId) {
        return legAccountingContext(leg, order, merchantAccountId, 0L);
    }

    /** P-7 直补商户模型：deduction=该腿营销抵扣额，用户扣净实付（腿额−抵扣），商户 C（净实付−腿费）。 */
    private AccountingTccContext legAccountingContext(PaymentLegDO leg, OrderDTO order, String merchantAccountId, long deduction) {
        AccountingTccContext ctx = new AccountingTccContext();
        ctx.setBizNo(leg.getId());
        ctx.setRefId(order.getId());
        ctx.setSubject("组合支付-" + order.getSubject() + "-腿" + leg.getLegSeq());
        ctx.setReversed(false);
        long payAmount = leg.getAmount() - deduction;
        long net = payAmount - leg.getFee();
        List<TransferLineDTO> lines = new ArrayList<>();
        if ("BALANCE".equals(leg.getMethod())) {
            lines.add(line(leg.getPayerAccountId(), "D", payAmount));
        } else {
            lines.add(line("SYS_CASH", "D", payAmount));
        }
        lines.add(line(merchantAccountId, "C", net));
        if (leg.getFee() > 0) {
            lines.add(line("SYS_FEE", "C", leg.getFee()));
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

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private PaymentDO require(String paymentId) {
        PaymentDO payment = paymentMapper.selectById(paymentId);
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
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return paymentMapper.selectByRequestId(payment.getRequestId());
        }
    }

    private void mirror(String paymentId) {
        ShardRowMirror mirror = shardRowMirror.getIfAvailable();
        if (mirror != null) {
            mirror.mirror("payment", paymentId);
        }
    }

    private PayResultDTO result(PaymentDO payment, boolean repeated) {
        PayResultDTO result = new PayResultDTO();
        PaymentDTO dto = PaymentConverter.toDTO(payment);
        if ("COMBO".equals(payment.getPayMode())) {
            dto.setLegs(legMapper.selectByPaymentId(payment.getId()).stream().map(ComboPayService::toLegDTO).toList());
        }
        result.setPayment(dto);
        result.setRepeated(repeated);
        return result;
    }

    public static PaymentLegDTO toLegDTO(PaymentLegDO leg) {
        PaymentLegDTO dto = new PaymentLegDTO();
        dto.setId(leg.getId());
        dto.setPaymentId(leg.getPaymentId());
        dto.setLegSeq(leg.getLegSeq());
        dto.setMethod(leg.getMethod());
        dto.setPayerAccountId(leg.getPayerAccountId());
        dto.setCardNo(maskCard(leg.getCardNo()));
        dto.setAmount(leg.getAmount());
        dto.setFee(leg.getFee());
        dto.setStatus(leg.getStatus());
        dto.setChannelBizNo(leg.getChannelBizNo());
        dto.setChannelCode(leg.getChannelCode());
        dto.setRefundedAmount(leg.getRefundedAmount());
        dto.setRefundedFee(leg.getRefundedFee());
        return dto;
    }

    private static String maskCard(String cardNo) {
        if (cardNo == null || cardNo.length() < 11) {
            return cardNo;
        }
        return cardNo.substring(0, 6) + "****" + cardNo.substring(cardNo.length() - 4);
    }
}