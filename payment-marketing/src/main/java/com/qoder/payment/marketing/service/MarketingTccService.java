package com.qoder.payment.marketing.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.marketing.ActivityApplyRequest;
import com.qoder.payment.facade.marketing.CouponUseRequest;
import com.qoder.payment.facade.marketing.MarketingTccContext;
import com.qoder.payment.facade.marketing.PointsUseRequest;
import com.qoder.payment.facade.marketing.RedPacketUseRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * P-7 支付宝式营销 TCC 编排（营销域侧，由 {@code MarketingTccFacadeImpl} 经 TccParticipantTemplate 驱动）。
 *
 * <p><b>原子性根治</b>：Try 只本地预占权益（无远程资金），失败时模板回滚本地事务即干净释放；
 * 补贴 {@code D SYS_MARKETING / C 商户}（直补商户）与 USE 分摊在 <b>Confirm</b> 统一落（幂等可重试，
 * 崩溃恢复续驱必达，无空回滚滞留）。Cancel 只本地释放预占（Try 未动资金，无补贴可反向）。
 *
 * <p>资金模型直补商户：accounting 分支冻结/扣用户净实付，营销分支 Confirm 补贴商户抵扣；
 * 商户实收=(净实付−fee)+抵扣=订单额−fee。各分支资金全本地原子，无远程资金嵌入回滚事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketingTccService {

    private final CouponService couponService;
    private final RedPacketService redPacketService;
    private final PointsService pointsService;
    private final ActivityService activityService;
    private final MarketingFundService fundService;
    private final MarketingSupport support;

    /** 询价：按确定性口径预算 Σ抵扣额（不预占、不动资金），供 payment 侧在 begin 前计算净实付。 */
    public long quoteTotal(MarketingTccContext ctx) {
        long total = 0L;
        for (MarketingTccContext.Pick pick : ctx.getPicks()) {
            total += resolveAmount(ctx, pick);
        }
        return total;
    }

    /** Try：逐项本地预占权益（无远程资金），返回 Σ抵扣额。超订单额抛异常（协调器全局 Cancel，本地回滚干净）。 */
    public long reserveAll(MarketingTccContext ctx) {
        long total = 0L;
        int seq = 0;
        for (MarketingTccContext.Pick pick : ctx.getPicks()) {
            seq++;
            total += reserveOne(ctx, pick, seq);
            if (total > ctx.getOrderAmount()) {
                throw new BizException(ResultCode.BIZ_ERROR,
                        "营销抵扣超订单额（实付不得为负）：Σ抵扣=" + total + " > 订单额=" + ctx.getOrderAmount());
            }
        }
        return total;
    }

    /** Confirm：逐项核销权益 + 补贴直补商户 + USE 分摊（幂等可重试，崩溃恢复续驱必达）。 */
    public void commitAll(MarketingTccContext ctx) {
        int seq = 0;
        for (MarketingTccContext.Pick pick : ctx.getPicks()) {
            seq++;
            switch (pick.getSource()) {
                case "COUPON" -> couponService.commit(couponRequest(ctx, pick, seq));
                case "RED_PACKET" -> redPacketService.commit(redPacketRequest(ctx, pick, seq));
                case "POINTS" -> pointsService.commit(pointsRequest(ctx, pick, seq));
                case "ACTIVITY" -> activityService.commit(activityRequest(ctx, pick, seq));
                default -> throw new BizException(ResultCode.PARAM_ERROR, "营销来源非法: " + pick.getSource());
            }
            long amount = resolveAmount(ctx, pick);
            if (amount <= 0) {
                continue;
            }
            String fundBizNo = fundBizNo(pick.getSource(), pick.getRefId(), ctx.getPaymentId());
            // 补贴直补商户（D SYS_MARKETING / C 商户），MKT 凭证幂等；Confirm 重试不重复动账。
            // USE 分摊已在 Try 本地落（随模板事务提交），此处不重复记。
            fundService.subsidy(fundBizNo, ctx.getMerchantAccountId(), amount, "营销补贴", ctx.getPaymentId());
        }
    }

    /** Cancel：逆序本地释放预占（Try 未动资金，无补贴可反向，绝不凭空反向）。 */
    public void releaseAll(MarketingTccContext ctx) {
        for (int i = ctx.getPicks().size() - 1; i >= 0; i--) {
            MarketingTccContext.Pick pick = ctx.getPicks().get(i);
            long amount = resolveAmount(ctx, pick);
            switch (pick.getSource()) {
                case "COUPON" -> couponService.release(pick.getRefId(), ctx.getPaymentId(), ctx.getAccountId(), amount, "TCC");
                case "RED_PACKET" -> redPacketService.release(pick.getRefId(), ctx.getPaymentId(), ctx.getAccountId(), amount, "TCC");
                case "POINTS" -> pointsService.release(ctx.getMemberId(), ctx.getPaymentId(), ctx.getAccountId(),
                        pick.getPoints() == null ? 0L : pick.getPoints(), amount, "TCC");
                case "ACTIVITY" -> activityService.release(pick.getRefId(), ctx.getPaymentId(), ctx.getAccountId(), amount, "TCC");
                default -> throw new BizException(ResultCode.PARAM_ERROR, "营销来源非法: " + pick.getSource());
            }
        }
    }

    private long reserveOne(MarketingTccContext ctx, MarketingTccContext.Pick pick, int seq) {
        return switch (pick.getSource()) {
            case "COUPON" -> couponService.reserve(couponRequest(ctx, pick, seq)).getAmount();
            case "RED_PACKET" -> redPacketService.reserve(redPacketRequest(ctx, pick, seq)).getAmount();
            case "POINTS" -> pointsService.reserve(pointsRequest(ctx, pick, seq)).getAmount();
            case "ACTIVITY" -> activityService.reserve(activityRequest(ctx, pick, seq)).getAmount();
            default -> throw new BizException(ResultCode.PARAM_ERROR, "营销来源非法: " + pick.getSource());
        };
    }

    private long resolveAmount(MarketingTccContext ctx, MarketingTccContext.Pick pick) {
        return switch (pick.getSource()) {
            case "COUPON" -> pick.getAmount() != null ? pick.getAmount() : couponService.get(pick.getRefId()).getAmount();
            case "RED_PACKET" -> pick.getAmount() != null ? pick.getAmount() : redPacketService.get(pick.getRefId()).getAmount();
            case "POINTS" -> (pick.getPoints() == null ? 0L : pick.getPoints()) / support.pointsRate();
            case "ACTIVITY" -> activityService.discountOf(activityService.get(pick.getRefId()), ctx.getOrderAmount());
            default -> 0L;
        };
    }

    /** 补贴凭证 bizNo（MKT 前缀，与营销资金恒等核对口径一致）。 */
    private static String fundBizNo(String source, String refId, String paymentId) {
        return switch (source) {
            case "COUPON" -> MarketingSupport.FUND_PREFIX + "C:" + refId + ":" + paymentId;
            case "RED_PACKET" -> MarketingSupport.FUND_PREFIX + "R:" + refId + ":" + paymentId;
            case "POINTS" -> MarketingSupport.FUND_PREFIX + "P:PU:" + paymentId;
            case "ACTIVITY" -> MarketingSupport.FUND_PREFIX + "A:" + refId + ":" + paymentId;
            default -> MarketingSupport.FUND_PREFIX + refId + ":" + paymentId;
        };
    }

    private CouponUseRequest couponRequest(MarketingTccContext ctx, MarketingTccContext.Pick pick, int seq) {
        CouponUseRequest r = new CouponUseRequest();
        r.setCouponId(pick.getRefId());
        r.setPaymentId(ctx.getPaymentId());
        r.setOrderId(ctx.getOrderId());
        r.setMemberId(ctx.getMemberId());
        r.setAccountId(ctx.getAccountId());
        r.setOrderAmount(ctx.getOrderAmount());
        r.setAmount(pick.getAmount());
        r.setRequestId("MOP:" + ctx.getPaymentId() + ":C:" + pick.getRefId());
        r.setOperator("P-7-TCC");
        return r;
    }

    private RedPacketUseRequest redPacketRequest(MarketingTccContext ctx, MarketingTccContext.Pick pick, int seq) {
        RedPacketUseRequest r = new RedPacketUseRequest();
        r.setRedPacketId(pick.getRefId());
        r.setPaymentId(ctx.getPaymentId());
        r.setOrderId(ctx.getOrderId());
        r.setMemberId(ctx.getMemberId());
        r.setAccountId(ctx.getAccountId());
        r.setOrderAmount(ctx.getOrderAmount());
        r.setAmount(pick.getAmount());
        r.setRequestId("MOP:" + ctx.getPaymentId() + ":R:" + pick.getRefId());
        r.setOperator("P-7-TCC");
        return r;
    }

    private PointsUseRequest pointsRequest(MarketingTccContext ctx, MarketingTccContext.Pick pick, int seq) {
        PointsUseRequest r = new PointsUseRequest();
        r.setMemberId(ctx.getMemberId());
        r.setPoints(pick.getPoints());
        r.setPaymentId(ctx.getPaymentId());
        r.setOrderId(ctx.getOrderId());
        r.setAccountId(ctx.getAccountId());
        r.setBizNo("PU:" + ctx.getPaymentId());
        r.setRequestId("MOP:" + ctx.getPaymentId() + ":P");
        r.setOperator("P-7-TCC");
        return r;
    }

    private ActivityApplyRequest activityRequest(MarketingTccContext ctx, MarketingTccContext.Pick pick, int seq) {
        ActivityApplyRequest r = new ActivityApplyRequest();
        r.setActivityId(pick.getRefId());
        r.setPaymentId(ctx.getPaymentId());
        r.setOrderId(ctx.getOrderId());
        r.setMemberId(ctx.getMemberId());
        r.setAccountId(ctx.getAccountId());
        r.setOrderAmount(ctx.getOrderAmount());
        r.setRequestId("MOP:" + ctx.getPaymentId() + ":A:" + pick.getRefId());
        r.setOperator("P-7-TCC");
        return r;
    }
}