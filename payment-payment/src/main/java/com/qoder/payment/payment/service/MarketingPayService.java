package com.qoder.payment.payment.service;

import com.qoder.payment.common.db.ShardRowMirror;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.marketing.ActivityApplyRequest;
import com.qoder.payment.facade.marketing.ActivityUseDTO;
import com.qoder.payment.facade.marketing.AllocItemDTO;
import com.qoder.payment.facade.marketing.AllocTargetDTO;
import com.qoder.payment.facade.marketing.BestPromotionDTO;
import com.qoder.payment.facade.marketing.BestPromotionRequest;
import com.qoder.payment.facade.marketing.CouponUseDTO;
import com.qoder.payment.facade.marketing.CouponUseRequest;
import com.qoder.payment.facade.marketing.MarketingAllocDTO;
import com.qoder.payment.facade.marketing.MarketingAllocRequest;
import com.qoder.payment.facade.marketing.MarketingFacade;
import com.qoder.payment.facade.marketing.MarketingTccContext;
import com.qoder.payment.facade.marketing.PointsLogDTO;
import com.qoder.payment.facade.marketing.PointsUseRequest;
import com.qoder.payment.facade.marketing.PromotionPickDTO;
import com.qoder.payment.facade.marketing.RedPacketUseRequest;
import com.qoder.payment.facade.payment.MarketingDeductionDTO;
import com.qoder.payment.facade.payment.MarketingDeductionLegDTO;
import com.qoder.payment.facade.payment.PayMarketing;
import com.qoder.payment.facade.payment.PayRequest;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.payment.dataobject.MarketingLegDO;
import com.qoder.payment.payment.dataobject.PaymentDO;
import com.qoder.payment.payment.mapper.MarketingLegMapper;
import com.qoder.payment.payment.mapper.PaymentMapper;
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
 * P-7 营销抵扣编排（券 → 红包 → 积分 → 活动）：<b>每步幂等 + 失败逆序退回 + 分摊守恒 + 明细落库可追溯</b>。
 *
 * <p><b>ride 兼容红线（最高优先级）</b>：{@link #requested(PayRequest)} 是<b>纯内存判定</b>
 * （只看 additive 的 {@code PayRequest.marketing} 字段），无营销诉求时<b>零 Dubbo 调用、零额外延迟、
 * 零 DB 写入</b>，fee 口径与 BALANCE 同步受理语义逐字节不变。本服务所有方法在无营销诉求时一律短路。
 *
 * <p><b>抵扣模型（整单抵扣）与守恒</b>：营销补贴先经既有 {@code AccountingFacade.post} 落
 * {@code D SYS_MARKETING / C 用户付款 BALANCE 户}（P2-A 既定口径，营销域无资金余额表），
 * 随后资金腿按<b>订单全额</b>扣款。于是：
 * <pre>
 *   用户净实付 payAmount = orderAmount − Σ营销抵扣（恒 ≥ 0）
 *   商户实收            = orderAmount − fee（fee 基数仍是订单全额，口径一字不改）
 *   SYS_MARKETING 净出账 = Σ营销抵扣
 *   守恒：orderAmount = payAmount + marketingAmount，marketingAmount = Σ来源 = Σ目标（diff=0）
 * </pre>
 * COMBO 衔接：Σ资金腿仍 == 订单额（组合受理强校验不变），补贴入其中一条 BALANCE 腿付款户，
 * 该腿余额校验延后到补贴到账后；其余腿与 CARD 腿校验不变 ⇒ 组合守恒与营销守恒同时成立。
 *
 * <p><b>幂等</b>：营销域既有幂等键<b>一字不改</b>——{@code (COUPON_USE,couponId:paymentId)} /
 * {@code (REDPACKET_USE,redPacketId:paymentId)} / {@code (POINTS_USE,bizNo)} /
 * {@code (ACTIVITY_APPLY,activityId:paymentId)}；payment 侧再加
 * {@code payment_marketing_leg} UNIQUE(payment_id,source,ref_id) + PENDING→USED CAS 双重防重。
 *
 * <p><b>失败回退</b>：任一步失败 → 逆序退回已核销权益（券/红包 REFUNDED + 积分回补 + 活动预算回滚，
 * 资金反向 {@code D 用户户 / C SYS_MARKETING}）→ 目标维度重分摊（Σ目标=Σ来源 diff=0）→ 支付单 FAILED。
 * 退回失败留 {@code ROLLBACK_PENDING}，由 {@code marketingRollback} 作业兜底重试（服务端最终一致）。
 *
 * <p><b>营销域未启用</b>：{@code payment.marketing.enabled=false} 或营销进程未启动
 * （{@code PP_MARKETING_ENABLED=false}）时，<b>无营销诉求的支付完全不受影响</b>；
 * 有营销诉求的支付优雅拒绝（业务错误码，非 5xx 崩溃），绝不静默放行或落半成品抵扣。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketingPayService {

    public static final String STATUS_NONE = "NONE";
    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_ROLLED_BACK = "ROLLED_BACK";
    public static final String STATUS_ROLLBACK_PENDING = "ROLLBACK_PENDING";

    public static final String LEG_PENDING = "PENDING";
    public static final String LEG_USED = "USED";
    public static final String LEG_ROLLED_BACK = "ROLLED_BACK";
    public static final String LEG_FAILED = "FAILED";

    public static final String SRC_COUPON = "COUPON";
    public static final String SRC_RED_PACKET = "RED_PACKET";
    public static final String SRC_POINTS = "POINTS";
    public static final String SRC_ACTIVITY = "ACTIVITY";

    private final MarketingLegMapper legMapper;
    private final PaymentMapper paymentMapper;
    private final ObjectProvider<ShardRowMirror> shardRowMirror;

    @DubboReference
    private MarketingFacade marketingFacade;

    @DubboReference
    private AccountFacade accountFacade;

    /** 编排总开关（§5.5 回滚手段）：false 时有营销诉求的支付优雅拒绝，无诉求支付零影响。 */
    @Value("${payment.marketing.enabled:true}")
    private boolean enabled;

    /** 待核销项（amount 为空表示由营销域按面额/兑换率/活动规则确定，核销后回填真实额）。 */
    private record Pick(String source, String refId, Long amount, Long points) {
    }

    // ==================== 短路判定（ride 红线：纯内存，零 RPC） ====================

    /** 是否有营销抵扣诉求。<b>纯内存判定</b>，无诉求恒 false ⇒ 编排完全短路。 */
    public boolean requested(PayRequest request) {
        return request != null && request.getMarketing() != null && request.getMarketing().hasAny();
    }

    /** 编排开关校验（本地配置读取，零 RPC）；仅在 requested=true 时调用。 */
    public void requireEnabled() {
        if (!enabled) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "营销抵扣编排已禁用（payment.marketing.enabled=false），请改用纯资金腿支付");
        }
    }

    // ==================== 受理编排 ====================

    /**
     * 支付受理时编排营销抵扣（paymentId 已落库后调用）。返回 Σ抵扣额（分）。
     * 失败时<b>逆序退回已核销权益</b>并抛出原异常（支付单由调用方置 FAILED）。
     */
    public long apply(PayRequest request, OrderDTO order, String paymentId, String payerAccountId) {
        PayMarketing spec = request.getMarketing();
        long orderAmount = order.getAmount();
        String memberId = spec.getMemberId();
        if (memberId == null || memberId.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "营销抵扣必须指定 memberId（权益归属会员）");
        }
        String accountId = spec.getAccountId() != null && !spec.getAccountId().isBlank()
                ? spec.getAccountId() : payerAccountId;
        if (accountId == null || accountId.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "营销抵扣必须有付款 BALANCE 户（CARD 单腿不支持营销抵扣）");
        }
        if (!accountId.equals(payerAccountId)) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "营销抵扣入账户必须等于付款 BALANCE 户（否则净实付=订单额−Σ抵扣不成立）: " + accountId);
        }
        AccountDTO account = call(() -> accountFacade.get(accountId)).orThrow();
        if (!"ACTIVE".equals(account.getStatus())) {
            throw new BizException(ResultCode.BIZ_ERROR, "抵扣入账户已冻结: " + accountId);
        }
        if (!memberId.equals(account.getMemberId())) {
            throw new BizException(ResultCode.PARAM_ERROR, "抵扣入账户不属于权益会员: " + accountId + " vs " + memberId);
        }

        List<Pick> picks = resolvePicks(spec, memberId, accountId, order, paymentId);
        if (picks.isEmpty()) {
            paymentMapper.updateMarketing(paymentId, 0L, STATUS_NONE);
            mirror(paymentId);
            return 0L;
        }
        List<MarketingLegDO> done = new ArrayList<>();
        long total = 0L;
        try {
            int seq = 0;
            for (Pick pick : picks) {
                seq++;
                MarketingLegDO row = ensureLeg(paymentId, seq, pick);
                if (LEG_USED.equals(row.getStatus())) {
                    // 幂等重放：已核销行直接计入，绝不二次核销
                    total += nz(row.getAmount());
                    done.add(row);
                    continue;
                }
                long amount = execute(row, pick, order, memberId, accountId, orderAmount, paymentId);
                total += amount;
                if (total > orderAmount) {
                    throw new BizException(ResultCode.BIZ_ERROR,
                            "营销抵扣超订单额（实付不得为负）：Σ抵扣=" + total + " > 订单额=" + orderAmount);
                }
                done.add(row);
            }
            allocateTargets(spec, order, paymentId, total);
        } catch (RuntimeException e) {
            log.warn("营销抵扣编排失败，逆序退回已核销权益 paymentId={} 已核销={} 原因={}",
                    paymentId, done.size(), e.getMessage());
            boolean clean = rollbackLegs(done, paymentId, "编排失败回退: " + e.getMessage());
            safeReallocate(paymentId, "ORCH-ROLLBACK");
            paymentMapper.updateMarketing(paymentId, netOf(legMapper.selectByPayment(paymentId)),
                    clean ? STATUS_ROLLED_BACK : STATUS_ROLLBACK_PENDING);
            mirror(paymentId);
            throw e;
        }
        paymentMapper.updateMarketing(paymentId, total, STATUS_APPLIED);
        mirror(paymentId);
        log.info("营销抵扣编排完成 paymentId={} memberId={} account={} 抵扣={} 实付={} 腿数={}",
                paymentId, memberId, accountId, total, orderAmount - total, done.size());
        return total;
    }

    // ==================== P-7 支付宝式 TCC 支撑（营销纳入支付全局事务边界） ====================

    /**
     * 构建营销 TCC 分支上下文：解析 picks + 校验入账户=付款 BALANCE 户。无营销诉求/无选中项返回 null。
     * 在 {@code coordinator.begin} 之前调用（纯解析，含 auto 模式 C-4 询价 RPC），失败即受理前拒绝。
     */
    public MarketingTccContext buildContext(PayRequest request, OrderDTO order, String paymentId,
                                          String payerAccountId, String merchantAccountId) {
        PayMarketing spec = request.getMarketing();
        String memberId = spec.getMemberId();
        if (memberId == null || memberId.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "营销抵扣必须指定 memberId（权益归属会员）");
        }
        String accountId = spec.getAccountId() != null && !spec.getAccountId().isBlank()
                ? spec.getAccountId() : payerAccountId;
        if (accountId == null || accountId.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "营销抵扣必须有付款 BALANCE 户（CARD 单腿不支持营销抵扣）");
        }
        if (!accountId.equals(payerAccountId)) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "营销抵扣入账户必须等于付款 BALANCE 户（否则净实付=订单额−Σ抵扣不成立）: " + accountId);
        }
        List<Pick> picks = resolvePicks(spec, memberId, accountId, order, paymentId);
        if (picks.isEmpty()) {
            return null;
        }
        MarketingTccContext ctx = new MarketingTccContext();
        ctx.setPaymentId(paymentId);
        ctx.setOrderId(order.getId());
        ctx.setMemberId(memberId);
        ctx.setAccountId(accountId);
        ctx.setMerchantAccountId(merchantAccountId);
        ctx.setOrderAmount(order.getAmount());
        for (Pick p : picks) {
            MarketingTccContext.Pick cp = new MarketingTccContext.Pick();
            cp.setSource(p.source());
            cp.setRefId(p.refId());
            cp.setAmount(p.amount());
            cp.setPoints(p.points());
            ctx.getPicks().add(cp);
        }
        return ctx;
    }

    /**
     * tryAll 成功后落 payment 侧营销明细 + APPLIED：抵扣额查营销域来源账（Try 预占即记 USE 分摊），
     * 每腿金额取 getAllocation 来源项。返回 Σ抵扣额（分）。
     */
    /** P-7 TCC：begin 前询价 Σ抵扣额（确定性口径，不预占），供 accounting 分支计算净实付。 */
    public long quoteTotal(MarketingTccContext ctx) {
        if (ctx == null || ctx.getPicks().isEmpty()) {
            return 0L;
        }
        return call(() -> marketingFacade.quoteDeduction(ctx)).orThrow();
    }

    public long recordApplied(String paymentId, MarketingTccContext ctx, PayMarketing spec, OrderDTO order) {
        // P-7 正确模型：补贴/USE 分摊在 Confirm 落，tryAll 后 alloc 尚未记 USE，故 total 与每腿金额用询价（确定性口径）。
        long total = quoteTotal(ctx);
        long now = System.currentTimeMillis();
        int seq = 0;
        for (MarketingTccContext.Pick cp : ctx.getPicks()) {
            seq++;
            Pick pick = new Pick(cp.getSource(), cp.getRefId(), cp.getAmount(), cp.getPoints());
            MarketingLegDO row = ensureLeg(paymentId, seq, pick);
            if (LEG_USED.equals(row.getStatus())) {
                continue;
            }
            long amount = quotePick(ctx, cp);
            legMapper.markUsed(row.getId(), cp.getRefId(), legBizNo(cp.getSource(), cp.getRefId(), paymentId),
                    "MOP:" + paymentId, amount, cp.getPoints(), now);
        }
        // 目标维度分摊（缺省单 ORDER 目标；Σ分摊=抵扣总额 diff=0，营销域强校验），与既有 apply 口径一致。
        allocateTargets(spec, order, paymentId, total);
        paymentMapper.updateMarketing(paymentId, total, STATUS_APPLIED);
        mirror(paymentId);
        log.info("营销 TCC 预占完成 paymentId={} 抵扣={} 腿数={}", paymentId, total, ctx.getPicks().size());
        return total;
    }

    /**
     * 协调器驱动营销 Cancel（释放预占 + 反向补贴）后，payment 侧明细/状态置 ROLLED_BACK。
     * <b>不再调营销域退回</b>——权益释放与资金反向已由 TCC 营销分支 Cancel 完成，此处仅同步 payment 侧视图。
     */
    public void markRolledBack(String paymentId) {
        long now = System.currentTimeMillis();
        for (MarketingLegDO leg : legMapper.selectByPayment(paymentId)) {
            if (LEG_USED.equals(leg.getStatus())) {
                legMapper.casStatus(leg.getId(), LEG_USED, LEG_ROLLED_BACK, "TCC 回滚", now);
            }
        }
        paymentMapper.updateMarketing(paymentId, 0L, STATUS_ROLLED_BACK);
        mirror(paymentId);
    }

    /** 单腿询价金额（确定性口径，与营销域 resolveAmount 一致）。 */
    private long quotePick(MarketingTccContext ctx, MarketingTccContext.Pick cp) {
        MarketingTccContext one = new MarketingTccContext();
        one.setPaymentId(ctx.getPaymentId());
        one.setOrderId(ctx.getOrderId());
        one.setMemberId(ctx.getMemberId());
        one.setAccountId(ctx.getAccountId());
        one.setMerchantAccountId(ctx.getMerchantAccountId());
        one.setOrderAmount(ctx.getOrderAmount());
        one.getPicks().add(cp);
        return call(() -> marketingFacade.quoteDeduction(one)).orThrow();
    }

    private static final String MarketingFundService_TYPE_USE = "USE";

    private static String legBizNo(String source, String refId, String paymentId) {
        return switch (source) {
            case SRC_COUPON -> "MKT:C:" + refId + ":" + paymentId;
            case SRC_RED_PACKET -> "MKT:R:" + refId + ":" + paymentId;
            case SRC_POINTS -> "MKT:P:PU:" + paymentId;
            case SRC_ACTIVITY -> "MKT:A:" + refId + ":" + paymentId;
            default -> "MKT:" + refId + ":" + paymentId;
        };
    }

    /** 解析待核销项：auto=调 C-4 最优组合；否则按显式列表，顺序恒为 券→红包→积分→活动。 */
    private List<Pick> resolvePicks(PayMarketing spec, String memberId, String accountId,
                                    OrderDTO order, String paymentId) {
        List<Pick> picks = new ArrayList<>();
        if (Boolean.TRUE.equals(spec.getAuto())) {
            BestPromotionRequest req = new BestPromotionRequest();
            req.setMemberId(memberId);
            req.setOrderAmount(order.getAmount());
            req.setOrderId(order.getId());
            req.setAccountId(accountId);
            req.setRules(spec.getRules());
            req.setRequestId("MOP:" + paymentId);
            BestPromotionDTO best = call(() -> marketingFacade.calcBestPromotion(req)).orThrow();
            for (PromotionPickDTO pick : best.getSelected()) {
                picks.add(new Pick(pick.getSource(), pick.getRefId(), pick.getAmount(), pick.getPoints()));
            }
            log.info("P-7 采用 C-4 最优组合 paymentId={} 抵扣={} 实付={} 选中={} fingerprint={}",
                    paymentId, best.getTotalDiscount(), best.getPayAmount(), picks.size(), best.getInputFingerprint());
            return picks;
        }
        if (spec.getCouponIds() != null) {
            for (String id : spec.getCouponIds()) {
                picks.add(new Pick(SRC_COUPON, id, null, null));
            }
        }
        if (spec.getRedPacketIds() != null) {
            for (String id : spec.getRedPacketIds()) {
                picks.add(new Pick(SRC_RED_PACKET, id, null, null));
            }
        }
        if (spec.getPoints() != null && spec.getPoints() > 0) {
            picks.add(new Pick(SRC_POINTS, memberId, null, spec.getPoints()));
        }
        if (spec.getActivityIds() != null) {
            for (String id : spec.getActivityIds()) {
                picks.add(new Pick(SRC_ACTIVITY, id, null, null));
            }
        }
        return picks;
    }

    /** 幂等落明细行（UNIQUE(payment_id,source,ref_id)；重放取既有行）。 */
    private MarketingLegDO ensureLeg(String paymentId, int seq, Pick pick) {
        MarketingLegDO existing = legMapper.selectByKey(paymentId, pick.source(), pick.refId());
        if (existing != null) {
            return existing;
        }
        long now = System.currentTimeMillis();
        MarketingLegDO row = new MarketingLegDO();
        row.setId("ML" + legMapper.nextId());
        row.setPaymentId(paymentId);
        row.setLegSeq(seq);
        row.setSource(pick.source());
        row.setRefId(pick.refId());
        row.setAmount(0L);
        row.setStatus(LEG_PENDING);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            legMapper.insert(row);
            return row;
        } catch (DuplicateKeyException e) {
            MarketingLegDO replay = legMapper.selectByKey(paymentId, pick.source(), pick.refId());
            if (replay == null) {
                throw e;
            }
            return replay;
        }
    }

    /** 执行一步核销（营销域既有幂等键不变），回填真实抵扣额与资金凭证号。 */
    private long execute(MarketingLegDO row, Pick pick, OrderDTO order, String memberId, String accountId,
                         long orderAmount, String paymentId) {
        long now = System.currentTimeMillis();
        String useId;
        String bizNo;
        String bizKey;
        long amount;
        Long points = null;
        switch (pick.source()) {
            case SRC_COUPON -> {
                CouponUseRequest r = new CouponUseRequest();
                r.setCouponId(pick.refId());
                r.setPaymentId(paymentId);
                r.setOrderId(order.getId());
                r.setMemberId(memberId);
                r.setAccountId(accountId);
                r.setOrderAmount(orderAmount);
                r.setAmount(pick.amount());
                r.setRequestId("MOP:" + paymentId + ":C:" + pick.refId());
                r.setOperator("P-7");
                CouponUseDTO use = call(() -> marketingFacade.useCoupon(r)).orThrow();
                useId = use.getId();
                bizNo = use.getBizNo();
                bizKey = r.getRequestId();
                amount = nz(use.getAmount());
            }
            case SRC_RED_PACKET -> {
                RedPacketUseRequest r = new RedPacketUseRequest();
                r.setRedPacketId(pick.refId());
                r.setPaymentId(paymentId);
                r.setOrderId(order.getId());
                r.setMemberId(memberId);
                r.setAccountId(accountId);
                r.setOrderAmount(orderAmount);
                r.setAmount(pick.amount());
                r.setRequestId("MOP:" + paymentId + ":R:" + pick.refId());
                r.setOperator("P-7");
                CouponUseDTO use = call(() -> marketingFacade.useRedPacket(r)).orThrow();
                useId = use.getId();
                bizNo = use.getBizNo();
                bizKey = r.getRequestId();
                amount = nz(use.getAmount());
            }
            case SRC_POINTS -> {
                PointsUseRequest r = new PointsUseRequest();
                r.setMemberId(memberId);
                r.setPoints(pick.points());
                r.setPaymentId(paymentId);
                r.setOrderId(order.getId());
                r.setAccountId(accountId);
                // 确定性 bizNo（营销域 (POINTS_USE,bizNo) 幂等键不变）；退回按此键，绝不换键重放
                bizKey = "PU:" + paymentId;
                r.setBizNo(bizKey);
                r.setRequestId("MOP:" + paymentId + ":P");
                r.setOperator("P-7");
                PointsLogDTO logRow = call(() -> marketingFacade.usePoints(r)).orThrow();
                useId = logRow.getId();
                bizNo = logRow.getFundBizNo();
                amount = nz(logRow.getAmount());
                points = pick.points();
            }
            case SRC_ACTIVITY -> {
                ActivityApplyRequest r = new ActivityApplyRequest();
                r.setActivityId(pick.refId());
                r.setPaymentId(paymentId);
                r.setOrderId(order.getId());
                r.setMemberId(memberId);
                r.setAccountId(accountId);
                r.setOrderAmount(orderAmount);
                r.setRequestId("MOP:" + paymentId + ":A:" + pick.refId());
                r.setOperator("P-7");
                ActivityUseDTO use = call(() -> marketingFacade.applyActivity(r)).orThrow();
                useId = use.getId();
                bizNo = use.getBizNo();
                bizKey = r.getRequestId();
                amount = nz(use.getAmount());
            }
            default -> throw new BizException(ResultCode.PARAM_ERROR, "营销来源非法: " + pick.source());
        }
        if (amount <= 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "营销核销额为 0，不受理: " + pick.source() + ":" + pick.refId());
        }
        int cas = legMapper.markUsed(row.getId(), useId, bizNo, bizKey, amount, points, now);
        if (cas != 1) {
            MarketingLegDO replay = legMapper.selectByKey(paymentId, pick.source(), pick.refId());
            if (replay != null && LEG_USED.equals(replay.getStatus())) {
                return nz(replay.getAmount());
            }
            throw new BizException(ResultCode.CONFLICT, "营销明细行并发变更，核销未受理: " + row.getId());
        }
        row.setStatus(LEG_USED);
        row.setUseId(useId);
        row.setBizNo(bizNo);
        row.setBizKey(bizKey);
        row.setAmount(amount);
        row.setPoints(points);
        return amount;
    }

    /** 目标维度分摊（缺省单 ORDER 目标=orderId；Σ分摊=抵扣总额 diff=0，营销域强校验）。 */
    private void allocateTargets(PayMarketing spec, OrderDTO order, String paymentId, long total) {
        MarketingAllocRequest req = new MarketingAllocRequest();
        req.setPaymentId(paymentId);
        req.setRequestId("MOP:" + paymentId);
        req.setTotalAmount(total);
        req.setOperator("P-7");
        List<AllocTargetDTO> targets = spec.getAllocTargets();
        if (targets == null || targets.isEmpty()) {
            AllocTargetDTO orderTarget = new AllocTargetDTO();
            orderTarget.setTargetType("ORDER");
            orderTarget.setTargetId(order.getId());
            orderTarget.setWeight(1L);
            targets = List.of(orderTarget);
            req.setMode("WEIGHT");
        } else {
            req.setMode(spec.getAllocMode() == null || spec.getAllocMode().isBlank() ? "WEIGHT" : spec.getAllocMode());
        }
        req.setTargets(targets);
        MarketingAllocDTO alloc = call(() -> marketingFacade.allocate(req)).orThrow();
        if (!Boolean.TRUE.equals(alloc.getBalanced()) || nz(alloc.getDiff()) != 0L) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "营销分摊不守恒（拒绝受理）：diff=" + alloc.getDiff() + " sourceDiff=" + alloc.getSourceDiff());
        }
    }

    // ==================== 回退（失败/关闭/撤销/退款营销腿） ====================

    /**
     * 营销抵扣回退（幂等）：逆序退回已核销权益 + 资金反向 + 目标维度重分摊。
     * 退回失败留 ROLLBACK_PENDING 由作业兜底，绝不静默吞掉资金不一致。
     */
    public MarketingDeductionDTO rollback(String paymentId, String requestId) {
        PaymentDO payment = requirePayment(paymentId);
        List<MarketingLegDO> legs = legMapper.selectByPayment(paymentId);
        if (legs.isEmpty()) {
            return deduction(payment, legs);
        }
        boolean clean = rollbackLegs(legs, paymentId, requestId);
        MarketingAllocDTO alloc = safeReallocate(paymentId, requestId);
        clean &= alloc != null && Boolean.TRUE.equals(alloc.getBalanced()) && nz(alloc.getDiff()) == 0L;
        long net = alloc != null ? nz(alloc.getSourceSum()) : netOf(legs);
        paymentMapper.updateMarketing(paymentId, net, clean ? STATUS_ROLLED_BACK : STATUS_ROLLBACK_PENDING);
        mirror(paymentId);
        log.info("营销抵扣回退 paymentId={} 腿数={} 全部退回={} requestId={}", paymentId, legs.size(), clean, requestId);
        return deduction(requirePayment(paymentId), legMapper.selectByPayment(paymentId));
    }

    /** 逆序退回（只处理 USED 行；营销域退回接口本身幂等，重放不重复反向动账）。 */
    private boolean rollbackLegs(List<MarketingLegDO> legs, String paymentId, String reason) {
        boolean clean = true;
        long now = System.currentTimeMillis();
        for (int i = legs.size() - 1; i >= 0; i--) {
            MarketingLegDO leg = legs.get(i);
            if (!LEG_USED.equals(leg.getStatus())) {
                continue;
            }
            try {
                switch (leg.getSource()) {
                    case SRC_COUPON -> call(() -> marketingFacade.refundCoupon(leg.getUseId())).orThrow();
                    case SRC_RED_PACKET -> call(() -> marketingFacade.refundRedPacket(leg.getUseId())).orThrow();
                    case SRC_POINTS -> call(() -> marketingFacade.refundPoints(
                            "PU:" + paymentId, "MOPR:" + paymentId)).orThrow();
                    case SRC_ACTIVITY -> call(() -> marketingFacade.refundActivity(leg.getUseId())).orThrow();
                    default -> throw new BizException(ResultCode.BIZ_ERROR, "营销来源非法: " + leg.getSource());
                }
                legMapper.casStatus(leg.getId(), LEG_USED, LEG_ROLLED_BACK, shortReason(reason), now);
                leg.setStatus(LEG_ROLLED_BACK);
            } catch (RuntimeException e) {
                clean = false;
                log.error("营销权益退回失败（留 ROLLBACK_PENDING 由作业兜底）paymentId={} leg={} source={}: {}",
                        paymentId, leg.getId(), leg.getSource(), e.getMessage());
            }
        }
        return clean;
    }

    /** 退回后目标维度重分摊（P2-A 遗留项）：Σ目标=Σ来源 diff=0；失败不吞（返回 null 交作业兜底）。 */
    private MarketingAllocDTO safeReallocate(String paymentId, String requestId) {
        try {
            return call(() -> marketingFacade.reallocate(paymentId, requestId)).orThrow();
        } catch (RuntimeException e) {
            log.error("营销目标维度重分摊失败（留待作业兜底）paymentId={}: {}", paymentId, e.getMessage());
            return null;
        }
    }

    /** TCC 回滚/关闭/撤销后的兜底回退（不抛异常，失败留痕由作业重试）。 */
    public void rollbackQuietly(String paymentId, String reason) {
        try {
            rollback(paymentId, reason);
        } catch (RuntimeException e) {
            log.error("营销抵扣兜底回退异常 paymentId={}: {}", paymentId, e.getMessage());
            try {
                paymentMapper.updateMarketingStatus(paymentId, STATUS_ROLLBACK_PENDING);
            } catch (RuntimeException ignore) {
                log.error("营销回退留痕失败 paymentId={}", paymentId);
            }
        }
    }

    /** 作业兜底：扫 ROLLBACK_PENDING 支付单重试回退（幂等，多实例由 ShedLock 互斥）。 */
    public int sweepRollbackPending(int limit) {
        if (!enabled) {
            return 0;
        }
        List<PaymentDO> pending = paymentMapper.selectMarketingRollbackPending(limit);
        int done = 0;
        for (PaymentDO payment : pending) {
            try {
                MarketingDeductionDTO dto = rollback(payment.getId(), "JOB-ROLLBACK");
                if (STATUS_ROLLED_BACK.equals(dto.getStatus())) {
                    done++;
                }
            } catch (RuntimeException e) {
                log.warn("营销回退兜底重试失败 paymentId={}: {}", payment.getId(), e.getMessage());
            }
        }
        if (done > 0) {
            log.info("营销抵扣回退兜底作业收敛 {} 单", done);
        }
        return done;
    }

    // ==================== 只读视图 ====================

    /** 抵扣明细 + 分摊 + 守恒三件套；<b>无营销诉求时零 RPC 短路</b>（ride 红线）。 */
    public MarketingDeductionDTO deduction(String paymentId) {
        return deduction(requirePayment(paymentId), legMapper.selectByPayment(paymentId));
    }

    private MarketingDeductionDTO deduction(PaymentDO payment, List<MarketingLegDO> legs) {
        long applied = 0L;
        for (MarketingLegDO leg : legs) {
            if (LEG_USED.equals(leg.getStatus()) || LEG_ROLLED_BACK.equals(leg.getStatus())) {
                applied += nz(leg.getAmount());
            }
        }
        long orderAmount = nz(payment.getAmount());
        MarketingDeductionDTO dto = new MarketingDeductionDTO();
        dto.setPaymentId(payment.getId());
        dto.setOrderId(payment.getOrderId());
        dto.setAccountId(payment.getPayerAccountId());
        dto.setOrderAmount(orderAmount);
        dto.setFee(nz(payment.getFee()));
        dto.setStatus(payment.getMarketingStatus() == null ? STATUS_NONE : payment.getMarketingStatus());
        dto.setLegs(legs.stream().map(MarketingPayService::toLegDTO).toList());
        dto.setRequestId(payment.getRequestId());
        dto.setCreatedAt(payment.getCreatedAt());
        dto.setUpdatedAt(System.currentTimeMillis());
        long net = netOf(legs);
        if (!legs.isEmpty() || nz(payment.getMarketingAmount()) > 0) {
            // 只有真实发生过营销编排才查营销域（无权益支付零 RPC、零延迟）
            try {
                MarketingAllocDTO alloc = call(() -> marketingFacade.getAllocation(payment.getId())).orThrow();
                dto.setAllocation(alloc);
                // 净额以营销域「来源分摊账」为权威（与资金凭证 1:1，含经营销域直接退回的部分）；
                // payment 侧明细行只记编排历史，外部退回不会改行状态，故净额必须取权威账而非行状态求和。
                net = nz(alloc.getSourceSum());
                final long netAmount = net;
                dto.setConservation(call(() -> marketingFacade.checkConservation(
                        payment.getId(), orderAmount, orderAmount - netAmount)).orThrow());
                dto.setMemberId(memberIdOf(legs));
            } catch (RuntimeException e) {
                log.warn("营销分摊/守恒查询不可达（明细仍如实返回）paymentId={}: {}", payment.getId(), e.getMessage());
            }
        }
        dto.setAppliedAmount(applied);
        dto.setNetAmount(net);
        dto.setRefundedAmount(applied - net);
        dto.setPayAmount(orderAmount - net);
        return dto;
    }

    private static String memberIdOf(List<MarketingLegDO> legs) {
        for (MarketingLegDO leg : legs) {
            if (SRC_POINTS.equals(leg.getSource())) {
                return leg.getRefId();
            }
        }
        return null;
    }

    /** P-7 退回后目标维度重分摊（幂等）：委托营销域 reallocate，回填权威净额快照，返回最新三件套。 */
    public MarketingDeductionDTO reallocate(String paymentId, String requestId) {
        PaymentDO payment = requirePayment(paymentId);
        MarketingAllocDTO alloc = call(() -> marketingFacade.reallocate(paymentId, requestId)).orThrow();
        if (!legMapper.selectByPayment(paymentId).isEmpty() || nz(payment.getMarketingAmount()) > 0) {
            // 经营销域直接退回后 payment.marketing_amount 快照会滞后，这里按权威来源净额收敛（状态不覆盖）
            paymentMapper.updateMarketing(paymentId, nz(alloc.getSourceSum()),
                    payment.getMarketingStatus() == null ? STATUS_APPLIED : payment.getMarketingStatus());
            mirror(paymentId);
        }
        return deduction(paymentId);
    }

    private static MarketingDeductionLegDTO toLegDTO(MarketingLegDO row) {
        MarketingDeductionLegDTO dto = new MarketingDeductionLegDTO();
        dto.setId(row.getId());
        dto.setPaymentId(row.getPaymentId());
        dto.setLegSeq(row.getLegSeq());
        dto.setSource(row.getSource());
        dto.setRefId(row.getRefId());
        dto.setUseId(row.getUseId());
        dto.setBizNo(row.getBizNo());
        dto.setBizKey(row.getBizKey());
        dto.setAmount(row.getAmount());
        dto.setPoints(row.getPoints());
        dto.setStatus(row.getStatus());
        dto.setFailReason(row.getFailReason());
        dto.setCreatedAt(row.getCreatedAt());
        dto.setUpdatedAt(row.getUpdatedAt());
        return dto;
    }

    // ==================== 内部 ====================

    /**
     * 营销域调用护栏：营销进程未启动（PP_MARKETING_ENABLED=false）/ 不可达时，
     * 把 Dubbo 无提供者异常转成<b>明确业务错误</b>（优雅拒绝，非 5xx 崩溃），并触发编排回退。
     */
    private <T> Result<T> call(Supplier<Result<T>> action) {
        try {
            Result<T> result = action.get();
            if (result == null) {
                throw new BizException(ResultCode.BIZ_ERROR, "营销域未启用或不可达（返回空），营销抵扣编排拒绝受理");
            }
            return result;
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            // 只留简短业务原因，绝不把 Dubbo 注册中心/下游地址原文透给调用方或写进 fail_reason（VARCHAR(256)）
            log.warn("营销域调用失败（未启用/不可达）: {}", e.getMessage());
            throw new BizException(ResultCode.BIZ_ERROR,
                    "营销域未启用或不可达，营销抵扣编排拒绝受理（可改用纯资金腿支付）");
        }
    }

    /** fail_reason 列宽护栏（VARCHAR(256)）：截断留痕，绝不因超长写入失败而让支付单悬挂 PROCESSING。 */
    public static String shortReason(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }

    private PaymentDO requirePayment(String paymentId) {
        PaymentDO payment = paymentMapper.selectById(paymentId);
        if (payment == null) {
            throw new BizException(ResultCode.NOT_FOUND, "支付单不存在: " + paymentId);
        }
        return payment;
    }

    private void mirror(String paymentId) {
        ShardRowMirror mirror = shardRowMirror.getIfAvailable();
        if (mirror != null) {
            mirror.mirror("payment", paymentId);
        }
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    /** 营销净抵扣（仍处 USED 的明细行之和；ROLLED_BACK 行已退回不计）。 */
    private static long netOf(List<MarketingLegDO> legs) {
        long net = 0L;
        for (MarketingLegDO leg : legs) {
            if (LEG_USED.equals(leg.getStatus())) {
                net += nz(leg.getAmount());
            }
        }
        return net;
    }
}