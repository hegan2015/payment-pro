package com.qoder.payment.marketing.facade;

import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.marketing.ActivityApplyRequest;
import com.qoder.payment.facade.marketing.ActivityDTO;
import com.qoder.payment.facade.marketing.ActivityUseDTO;
import com.qoder.payment.facade.marketing.AvailablePromotionDTO;
import com.qoder.payment.facade.marketing.ClaimRequest;
import com.qoder.payment.facade.marketing.CouponDTO;
import com.qoder.payment.facade.marketing.CouponIssueRequest;
import com.qoder.payment.facade.marketing.CouponUseDTO;
import com.qoder.payment.facade.marketing.CouponUseRequest;
import com.qoder.payment.facade.marketing.MarketingAllocDTO;
import com.qoder.payment.facade.marketing.MarketingAllocRequest;
import com.qoder.payment.facade.marketing.MarketingAuditDTO;
import com.qoder.payment.facade.marketing.MarketingConservationDTO;
import com.qoder.payment.facade.marketing.MarketingFacade;
import com.qoder.payment.facade.marketing.MarketingFundCheckDTO;
import com.qoder.payment.facade.marketing.MarketingTemplateDTO;
import com.qoder.payment.facade.marketing.PointsAccountDTO;
import com.qoder.payment.facade.marketing.PointsEarnRequest;
import com.qoder.payment.facade.marketing.PointsFreezeRequest;
import com.qoder.payment.facade.marketing.PointsLogDTO;
import com.qoder.payment.facade.marketing.PointsUseRequest;
import com.qoder.payment.facade.marketing.RedPacketDTO;
import com.qoder.payment.facade.marketing.RedPacketIssueRequest;
import com.qoder.payment.facade.marketing.RedPacketUseRequest;
import com.qoder.payment.marketing.mapper.MarketingAuditMapper;
import com.qoder.payment.marketing.service.ActivityService;
import com.qoder.payment.marketing.service.CouponService;
import com.qoder.payment.marketing.service.MarketingAllocService;
import com.qoder.payment.marketing.service.MarketingFundService;
import com.qoder.payment.marketing.service.MarketingSupport;
import com.qoder.payment.marketing.service.PointsService;
import com.qoder.payment.marketing.service.RedPacketService;
import com.qoder.payment.marketing.service.TemplateService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

/**
 * 营销域 Dubbo 边界（X-3a/X-3b）。薄适配层：一律 {@link FacadeSupport#execute} 包装为 Result，
 * 不向调用方抛异常；业务全在 service 层（幂等 + CAS + 复式记账 + 审计）。
 */
@DubboService
@RequiredArgsConstructor
public class MarketingFacadeImpl implements MarketingFacade {

    private final TemplateService templateService;
    private final CouponService couponService;
    private final RedPacketService redPacketService;
    private final PointsService pointsService;
    private final ActivityService activityService;
    private final MarketingAllocService allocService;
    private final MarketingFundService fundService;
    private final MarketingSupport support;
    private final MarketingAuditMapper auditMapper;
    /** P2-E C-4 最优组合计算（additive，只读不核销）。 */
    private final com.qoder.payment.marketing.service.PromotionCalcService promotionCalcService;

    // ---------------- X-3a 模板 ----------------

    @Override
    public Result<MarketingTemplateDTO> saveTemplate(MarketingTemplateDTO template) {
        return FacadeSupport.execute(() -> templateService.save(template));
    }

    @Override
    public Result<MarketingTemplateDTO> getTemplate(String templateId) {
        return FacadeSupport.execute(() -> templateService.get(templateId));
    }

    @Override
    public Result<List<MarketingTemplateDTO>> listTemplates(String kind, String status) {
        return FacadeSupport.execute(() -> templateService.list(kind, status));
    }

    // ---------------- X-3a 优惠券 ----------------

    @Override
    public Result<CouponDTO> issueCoupon(CouponIssueRequest request) {
        return FacadeSupport.execute(() -> couponService.issue(request));
    }

    @Override
    public Result<CouponDTO> claimCoupon(ClaimRequest request) {
        return FacadeSupport.execute(() -> couponService.claim(request));
    }

    @Override
    public Result<CouponDTO> getCoupon(String couponId) {
        return FacadeSupport.execute(() -> couponService.get(couponId));
    }

    @Override
    public Result<List<CouponDTO>> listCoupons(String memberId, String status) {
        return FacadeSupport.execute(() -> couponService.list(memberId, status));
    }

    @Override
    public Result<CouponUseDTO> useCoupon(CouponUseRequest request) {
        return FacadeSupport.execute(() -> couponService.use(request));
    }

    @Override
    public Result<CouponUseDTO> refundCoupon(String couponUseId) {
        return FacadeSupport.execute(() -> couponService.refund(couponUseId));
    }

    // ---------------- X-3a 红包 ----------------

    @Override
    public Result<RedPacketDTO> issueRedPacket(RedPacketIssueRequest request) {
        return FacadeSupport.execute(() -> redPacketService.issue(request));
    }

    @Override
    public Result<RedPacketDTO> claimRedPacket(ClaimRequest request) {
        return FacadeSupport.execute(() -> redPacketService.claim(request));
    }

    @Override
    public Result<RedPacketDTO> getRedPacket(String redPacketId) {
        return FacadeSupport.execute(() -> redPacketService.get(redPacketId));
    }

    @Override
    public Result<List<RedPacketDTO>> listRedPackets(String memberId, String status) {
        return FacadeSupport.execute(() -> redPacketService.list(memberId, status));
    }

    @Override
    public Result<CouponUseDTO> useRedPacket(RedPacketUseRequest request) {
        return FacadeSupport.execute(() -> redPacketService.use(request));
    }

    @Override
    public Result<CouponUseDTO> refundRedPacket(String redPacketUseId) {
        return FacadeSupport.execute(() -> redPacketService.refund(redPacketUseId));
    }

    // ---------------- X-3a 可用优惠 / 过期 ----------------

    @Override
    public Result<AvailablePromotionDTO> listAvailable(String memberId, Long orderAmount) {
        return FacadeSupport.execute(() -> {
            MarketingSupport.require(MarketingSupport.notBlank(memberId), "memberId 必填");
            AvailablePromotionDTO dto = new AvailablePromotionDTO();
            dto.setMemberId(memberId);
            dto.setOrderAmount(orderAmount);
            List<CouponDTO> coupons = couponService.available(memberId, orderAmount);
            List<RedPacketDTO> packets = redPacketService.available(memberId);
            dto.setCoupons(coupons);
            dto.setRedPackets(packets);
            dto.setActivities(activityService.list(orderAmount));
            PointsAccountDTO points = pointsService.account(memberId);
            dto.setPointsBalance(MarketingSupport.nz(points.getBalance()));
            dto.setPointsAvailable(MarketingSupport.nz(points.getAvailable()));
            dto.setPointsAmount(MarketingSupport.nz(points.getAvailable()) / support.pointsRate());
            long voucherAmount = 0L;
            for (CouponDTO coupon : coupons) {
                voucherAmount += MarketingSupport.nz(coupon.getAmount());
            }
            for (RedPacketDTO packet : packets) {
                voucherAmount += MarketingSupport.nz(packet.getAmount());
            }
            dto.setVoucherAmount(voucherAmount);
            return dto;
        });
    }

    @Override
    public Result<Integer> expireVouchers() {
        return FacadeSupport.execute(() -> couponService.expireDue() + redPacketService.expireDue());
    }

    // ---------------- X-3b 积分 ----------------

    @Override
    public Result<PointsAccountDTO> pointsAccount(String memberId) {
        return FacadeSupport.execute(() -> pointsService.account(memberId));
    }

    @Override
    public Result<PointsLogDTO> earnPoints(PointsEarnRequest request) {
        return FacadeSupport.execute(() -> pointsService.earn(request));
    }

    @Override
    public Result<PointsLogDTO> usePoints(PointsUseRequest request) {
        return FacadeSupport.execute(() -> pointsService.use(request));
    }

    @Override
    public Result<PointsLogDTO> freezePoints(PointsFreezeRequest request) {
        return FacadeSupport.execute(() -> pointsService.freeze(request));
    }

    @Override
    public Result<PointsLogDTO> unfreezePoints(PointsFreezeRequest request) {
        return FacadeSupport.execute(() -> pointsService.unfreeze(request));
    }

    @Override
    public Result<PointsLogDTO> refundPoints(String origBizNo, String requestId) {
        return FacadeSupport.execute(() -> pointsService.refund(origBizNo, requestId));
    }

    @Override
    public Result<List<PointsLogDTO>> pointsLogs(String memberId, int limit) {
        return FacadeSupport.execute(() -> pointsService.logs(memberId, limit));
    }

    @Override
    public Result<Long> expirePoints() {
        return FacadeSupport.execute(pointsService::expireDue);
    }

    // ---------------- X-3b 活动 ----------------

    @Override
    public Result<ActivityDTO> saveActivity(ActivityDTO activity) {
        return FacadeSupport.execute(() -> activityService.save(activity));
    }

    @Override
    public Result<ActivityDTO> getActivity(String activityId) {
        return FacadeSupport.execute(() -> activityService.get(activityId));
    }

    @Override
    public Result<List<ActivityDTO>> listActivities(Long orderAmount) {
        return FacadeSupport.execute(() -> activityService.list(orderAmount));
    }

    @Override
    public Result<ActivityUseDTO> applyActivity(ActivityApplyRequest request) {
        return FacadeSupport.execute(() -> activityService.apply(request));
    }

    @Override
    public Result<ActivityUseDTO> refundActivity(String activityUseId) {
        return FacadeSupport.execute(() -> activityService.refund(activityUseId));
    }

    // ---------------- X-3b 分摊联动（P-7 预留） ----------------

    @Override
    public Result<MarketingAllocDTO> allocate(MarketingAllocRequest request) {
        return FacadeSupport.execute(() -> allocService.allocate(request));
    }

    @Override
    public Result<MarketingAllocDTO> getAllocation(String paymentId) {
        return FacadeSupport.execute(() -> allocService.getAllocation(paymentId));
    }

    @Override
    public Result<MarketingConservationDTO> checkConservation(String paymentId, Long orderAmount, Long fundAmount) {
        return FacadeSupport.execute(() -> allocService.checkConservation(paymentId, orderAmount, fundAmount));
    }

    // ---------------- 资金护航 / 审计 ----------------

    @Override
    public Result<MarketingFundCheckDTO> fundCheck() {
        return FacadeSupport.execute(fundService::fundCheck);
    }

    @Override
    public Result<List<MarketingAuditDTO>> listAudit(String entityId, int limit) {
        return FacadeSupport.execute(() -> auditMapper.selectRecent(entityId,
                limit <= 0 ? 50 : Math.min(limit, 500)));
    }

    // ---------------- P2 · E 泳道 additive（C-4 最优组合 / P-7 退回后重分摊） ----------------

    @Override
    public Result<com.qoder.payment.facade.marketing.BestPromotionDTO> calcBestPromotion(
            com.qoder.payment.facade.marketing.BestPromotionRequest request) {
        return FacadeSupport.execute(() -> promotionCalcService.calc(request));
    }

    @Override
    public Result<MarketingAllocDTO> reallocate(String paymentId, String requestId) {
        return FacadeSupport.execute(() -> allocService.reallocate(paymentId, requestId));
    }
}