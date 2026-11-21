package com.qoder.payment.marketing.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.marketing.ClaimRequest;
import com.qoder.payment.facade.marketing.CouponDTO;
import com.qoder.payment.facade.marketing.CouponIssueRequest;
import com.qoder.payment.facade.marketing.CouponUseDTO;
import com.qoder.payment.facade.marketing.CouponUseRequest;
import com.qoder.payment.facade.marketing.MarketingTemplateDTO;
import com.qoder.payment.marketing.dataobject.MarketingClaimDO;
import com.qoder.payment.marketing.mapper.CouponMapper;
import com.qoder.payment.marketing.mapper.CouponUseMapper;
import com.qoder.payment.marketing.mapper.MarketingClaimMapper;
import com.qoder.tcc.IdempotentTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * X-3a 优惠券：模板发放 / 领取 / 核销 / 过期 / 退回，全程状态机 CAS + 幂等 + 审计留痕。
 *
 * <p><b>幂等键</b>：发放 {@code (COUPON_ISSUE, bizNo)} + {@code coupon.request_id} UNIQUE；
 * 领取 {@code (CLAIM, templateId:memberId:bizNo)} + {@code marketing_claim} UNIQUE；
 * 核销 {@code (COUPON_USE, couponId:paymentId)} + {@code coupon_use.request_id} UNIQUE
 * + {@code coupon_use.coupon_id} UNIQUE + 券 {@code AVAILABLE→USED} CAS（四重防重）。
 *
 * <p><b>资金</b>：核销即营销补贴，经 {@link MarketingFundService} 走 accounting 复式记账
 * {@code D SYS_MARKETING / C 用户户}；退回反向。营销域<b>不自建资金余额表</b>。
 *
 * <p><b>状态机</b>：AVAILABLE → USED → REFUNDED（终态，券作废）；AVAILABLE → EXPIRED。
 * 退回后券不回到 AVAILABLE：{@code coupon_use.coupon_id} UNIQUE 要求一券一生只核销一次，
 * 需重新可用必须重新发放（新券新幂等键），杜绝「退回—再核销」绕开唯一键的重复抵扣。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_FROZEN = "FROZEN";
    public static final String STATUS_USED = "USED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_REFUNDED = "REFUNDED";
    public static final String SOURCE = "COUPON";

    private final CouponMapper couponMapper;
    private final CouponUseMapper useMapper;
    private final MarketingClaimMapper claimMapper;
    private final TemplateService templateService;
    private final MarketingSupport support;
    private final MarketingFundService fundService;
    private final IdempotentTemplate idempotentTemplate;

    // ---------------- 发放 ----------------

    public CouponDTO issue(CouponIssueRequest request) {
        MarketingSupport.require(request != null, "请求必填");
        MarketingSupport.require(MarketingSupport.notBlank(request.getMemberId()), "memberId 必填");
        MarketingSupport.requireKey(request.getBizNo());
        MarketingSupport.require(request.getBizNo().length() <= 61, "bizNo 需 ≤61 字符（派生 requestId 前缀 CI:）");
        String requestId = "CI:" + request.getBizNo();
        return idempotentTemplate.execute("COUPON_ISSUE", request.getBizNo(), CouponDTO.class, () -> {
            CouponDTO existing = couponMapper.selectByRequest(requestId);
            if (existing != null) {
                return existing;
            }
            MarketingTemplateDTO template = null;
            if (MarketingSupport.notBlank(request.getTemplateId())) {
                template = templateService.consume(request.getTemplateId(), request.getMemberId(),
                        TemplateService.KIND_COUPON, false);
            }
            long amount = request.getAmount() != null ? request.getAmount()
                    : (template != null ? MarketingSupport.nz(template.getAmount()) : 0L);
            MarketingSupport.require(amount > 0, "券面额必须为正整数（分）");
            long threshold = request.getThreshold() != null ? request.getThreshold()
                    : (template != null ? MarketingSupport.nz(template.getThreshold()) : 0L);
            MarketingSupport.require(threshold >= 0, "threshold 不得为负");
            long now = System.currentTimeMillis();
            CouponDTO coupon = new CouponDTO();
            coupon.setId(support.newId("C"));
            coupon.setMemberId(request.getMemberId());
            coupon.setTemplateId(template == null ? null : template.getId());
            coupon.setAmount(amount);
            coupon.setThreshold(threshold);
            coupon.setStatus(STATUS_AVAILABLE);
            coupon.setExpireAt(templateService.resolveExpireAt(request.getExpireAt(), request.getValidDays(), template));
            coupon.setRequestId(requestId);
            coupon.setBizNo(request.getBizNo());
            coupon.setOperator(request.getOperator());
            coupon.setCreatedAt(now);
            coupon.setUpdatedAt(now);
            couponMapper.insert(coupon);
            support.audit("COUPON", coupon.getId(), "ISSUE", null, STATUS_AVAILABLE, amount, requestId,
                    request.getOperator(), "发券 member=" + coupon.getMemberId() + " threshold=" + threshold
                            + " expireAt=" + coupon.getExpireAt());
            return coupon;
        });
    }

    // ---------------- 领取 ----------------

    public CouponDTO claim(ClaimRequest request) {
        MarketingSupport.require(request != null, "请求必填");
        MarketingSupport.require(MarketingSupport.notBlank(request.getTemplateId()), "templateId 必填");
        MarketingSupport.require(MarketingSupport.notBlank(request.getMemberId()), "memberId 必填");
        MarketingSupport.requireKey(request.getBizNo());
        String key = request.getTemplateId() + ":" + request.getMemberId() + ":" + request.getBizNo();
        MarketingSupport.requireKey(key);
        return idempotentTemplate.execute("CLAIM", key, CouponDTO.class, () -> {
            MarketingClaimDO existing = claimMapper.selectByKey(request.getTemplateId(), request.getMemberId(),
                    request.getBizNo());
            if (existing != null) {
                CouponDTO coupon = couponMapper.selectById(existing.getVoucherId());
                MarketingSupport.requireFound(coupon, "领取留痕对应券缺失: " + existing.getVoucherId());
                return coupon;
            }
            MarketingTemplateDTO template = templateService.consume(request.getTemplateId(), request.getMemberId(),
                    TemplateService.KIND_COUPON, true);
            long now = System.currentTimeMillis();
            String claimId = support.newId("CL");
            CouponDTO coupon = new CouponDTO();
            coupon.setId(support.newId("C"));
            coupon.setMemberId(request.getMemberId());
            coupon.setTemplateId(template.getId());
            coupon.setAmount(template.getAmount());
            coupon.setThreshold(MarketingSupport.nz(template.getThreshold()));
            coupon.setStatus(STATUS_AVAILABLE);
            coupon.setExpireAt(templateService.resolveExpireAt(null, null, template));
            coupon.setRequestId("CC:" + claimId);
            coupon.setBizNo(key);
            coupon.setOperator(request.getOperator());
            coupon.setCreatedAt(now);
            coupon.setUpdatedAt(now);
            couponMapper.insert(coupon);

            MarketingClaimDO claim = new MarketingClaimDO();
            claim.setId(claimId);
            claim.setTemplateId(template.getId());
            claim.setMemberId(request.getMemberId());
            claim.setKind(TemplateService.KIND_COUPON);
            claim.setVoucherId(coupon.getId());
            claim.setBizNo(request.getBizNo());
            claim.setCreatedAt(now);
            claimMapper.insert(claim);
            support.audit("COUPON", coupon.getId(), "CLAIM", null, STATUS_AVAILABLE, coupon.getAmount(), key,
                    request.getOperator(), "领券 template=" + template.getId());
            return coupon;
        });
    }

    // ---------------- 查询 ----------------

    public CouponDTO get(String couponId) {
        CouponDTO coupon = couponMapper.selectById(couponId);
        MarketingSupport.requireFound(coupon, "券不存在: " + couponId);
        return coupon;
    }

    public List<CouponDTO> list(String memberId, String status) {
        MarketingSupport.require(MarketingSupport.notBlank(memberId), "memberId 必填");
        return couponMapper.selectByMember(memberId, status);
    }

    public List<CouponDTO> available(String memberId, Long orderAmount) {
        return couponMapper.selectAvailable(memberId, System.currentTimeMillis(), orderAmount);
    }

    // ---------------- 核销 ----------------

    public CouponUseDTO use(CouponUseRequest request) {
        fundService.requireEnabled();
        MarketingSupport.require(request != null, "请求必填");
        MarketingSupport.require(MarketingSupport.notBlank(request.getCouponId()), "couponId 必填");
        MarketingSupport.require(MarketingSupport.notBlank(request.getPaymentId()), "paymentId 必填");
        String key = request.getCouponId() + ":" + request.getPaymentId();
        MarketingSupport.requireKey(key);
        String requestId = MarketingSupport.notBlank(request.getRequestId()) ? request.getRequestId() : "CU:" + key;
        MarketingSupport.requireKey(requestId);
        String bizNo = MarketingSupport.FUND_PREFIX + "C:" + key;
        MarketingSupport.requireKey(bizNo);
        return idempotentTemplate.execute("COUPON_USE", key, CouponUseDTO.class, () -> {
            CouponUseDTO byRequest = useMapper.selectByRequest(requestId);
            if (byRequest != null) {
                return byRequest;
            }
            CouponUseDTO byCoupon = useMapper.selectByCoupon(request.getCouponId());
            if (byCoupon != null) {
                if (byCoupon.getPaymentId().equals(request.getPaymentId())) {
                    return byCoupon;
                }
                throw new BizException(ResultCode.CONFLICT, "券已核销于其它支付单: " + byCoupon.getPaymentId());
            }
            CouponDTO coupon = get(request.getCouponId());
            if (!STATUS_AVAILABLE.equals(coupon.getStatus())) {
                throw new BizException(ResultCode.CONFLICT, "券状态不可核销: " + coupon.getStatus());
            }
            long now = System.currentTimeMillis();
            if (coupon.getExpireAt() <= now) {
                throw new BizException(ResultCode.BIZ_ERROR, "券已过期，不可核销: " + coupon.getId());
            }
            if (MarketingSupport.notBlank(request.getMemberId())
                    && !request.getMemberId().equals(coupon.getMemberId())) {
                throw new BizException(ResultCode.PARAM_ERROR, "券归属会员与请求不一致");
            }
            long amount = request.getAmount() != null ? request.getAmount() : coupon.getAmount();
            MarketingSupport.require(amount > 0 && amount <= coupon.getAmount(),
                    "核销额需在 (0, 券面额] 内：amount=" + amount + " face=" + coupon.getAmount());
            if (request.getOrderAmount() != null) {
                MarketingSupport.require(request.getOrderAmount() >= coupon.getThreshold(),
                        "未达券门槛：orderAmount=" + request.getOrderAmount() + " threshold=" + coupon.getThreshold());
                long already = fundService.sourceNet(request.getPaymentId());
                MarketingSupport.require(already + amount <= request.getOrderAmount(),
                        "营销抵扣超订单额：已抵扣 " + already + " + 本次 " + amount + " > " + request.getOrderAmount());
            }
            String accountId = fundService.resolveAccount(coupon.getMemberId(), request.getAccountId());
            String useId = support.newId("CU");
            int cas = couponMapper.casUse(coupon.getId(), useId, now);
            if (cas != 1) {
                throw new BizException(ResultCode.CONFLICT, "券状态并发变更，核销未受理: " + coupon.getId());
            }
            // 先动账（账务 bizNo 确定性幂等）再登记：任一步失败重试都不会重复补贴
            fundService.subsidy(bizNo, accountId, amount, "营销券核销", request.getPaymentId());

            CouponUseDTO use = new CouponUseDTO();
            use.setId(useId);
            use.setKind(SOURCE);
            use.setVoucherId(coupon.getId());
            use.setPaymentId(request.getPaymentId());
            use.setOrderId(request.getOrderId());
            use.setMemberId(coupon.getMemberId());
            use.setAccountId(accountId);
            use.setAmount(amount);
            use.setBizNo(bizNo);
            use.setStatus(STATUS_USED);
            use.setRequestId(requestId);
            use.setOperator(request.getOperator());
            use.setCreatedAt(now);
            use.setUpdatedAt(now);
            useMapper.insert(use);
            fundService.recordSource(request.getPaymentId(), SOURCE, useId, amount,
                    MarketingFundService.TYPE_USE, bizNo);
            support.audit("COUPON", coupon.getId(), "USE", STATUS_AVAILABLE, STATUS_USED, amount, bizNo,
                    request.getOperator(), "核销 payment=" + request.getPaymentId() + " account=" + accountId
                            + " useId=" + useId);
            return use;
        });
    }

    // ---------------- TCC 三阶段（P-7 支付宝式：营销纳入支付全局事务边界） ----------------

    /**
     * TCC Try：预占券 AVAILABLE→FROZEN + 预补贴 {@code D SYS_MARKETING / C 用户户}（可补偿）+ 来源分摊 USE 行。
     * 此时 xid 已存在，崩溃恢复由协调器驱动 Cancel 反向补贴。幂等 by {@code (COUPON_RESERVE, couponId:paymentId)}。
     */
    public CouponUseDTO reserve(CouponUseRequest request) {
        fundService.requireEnabled();
        MarketingSupport.require(request != null && MarketingSupport.notBlank(request.getCouponId()), "couponId 必填");
        MarketingSupport.require(MarketingSupport.notBlank(request.getPaymentId()), "paymentId 必填");
        String key = request.getCouponId() + ":" + request.getPaymentId();
        String bizNo = MarketingSupport.FUND_PREFIX + "C:" + key;
        MarketingSupport.requireKey(bizNo);
        return idempotentTemplate.execute("COUPON_RESERVE", key, CouponUseDTO.class, () -> {
            CouponDTO coupon = get(request.getCouponId());
            long amount = validateUsable(coupon, request);
            String accountId = fundService.resolveAccount(coupon.getMemberId(), request.getAccountId());
            long now = System.currentTimeMillis();
            if (!STATUS_FROZEN.equals(coupon.getStatus())) {
                int cas = couponMapper.casReserve(coupon.getId(), now);
                if (cas != 1) {
                    throw new BizException(ResultCode.CONFLICT, "券状态并发变更，预占未受理: " + coupon.getId());
                }
            }
            // P-7 正确模型：Try 本地预占权益 + USE 分摊（本地，随模板事务成功提交；失败本地回滚无 USE）。
            // 远程补贴在 Confirm 落（幂等可重试）。故 SUCCESS=USE+补贴平衡，FAILED=USE 回滚无补贴平衡。
            fundService.recordSource(request.getPaymentId(), SOURCE, coupon.getId(), amount,
                    MarketingFundService.TYPE_USE, bizNo);
            support.audit("COUPON", coupon.getId(), "RESERVE", STATUS_AVAILABLE, STATUS_FROZEN, amount, bizNo,
                    request.getOperator(), "TCC 预占 payment=" + request.getPaymentId());
            return frozenDto(coupon, request, accountId, amount, bizNo);
        });
    }

    /** TCC Confirm：正式核销 FROZEN→USED + 落核销单（预补贴与分摊已在 Try）。幂等 by coupon_use.coupon_id UNIQUE。 */
    public CouponUseDTO commit(CouponUseRequest request) {
        fundService.requireEnabled();
        String key = request.getCouponId() + ":" + request.getPaymentId();
        String bizNo = MarketingSupport.FUND_PREFIX + "C:" + key;
        return idempotentTemplate.execute("COUPON_USE", key, CouponUseDTO.class, () -> {
            CouponUseDTO byCoupon = useMapper.selectByCoupon(request.getCouponId());
            if (byCoupon != null) {
                return byCoupon;
            }
            CouponDTO coupon = get(request.getCouponId());
            long amount = validateUsable(coupon, request);
            String accountId = fundService.resolveAccount(coupon.getMemberId(), request.getAccountId());
            long now = System.currentTimeMillis();
            String useId = support.newId("CU");
            int cas = couponMapper.casCommit(coupon.getId(), useId, now);
            if (cas != 1) {
                throw new BizException(ResultCode.CONFLICT, "券非预占态，核销未受理: " + coupon.getStatus());
            }
            CouponUseDTO use = frozenDto(coupon, request, accountId, amount, bizNo);
            use.setId(useId);
            use.setStatus(STATUS_USED);
            useMapper.insert(use);
            support.audit("COUPON", coupon.getId(), "USE", STATUS_FROZEN, STATUS_USED, amount, bizNo,
                    request.getOperator(), "TCC 核销 payment=" + request.getPaymentId() + " useId=" + useId);
            return use;
        });
    }

    /**
     * TCC Cancel：释放预占 FROZEN→AVAILABLE + 反向补贴 + 来源分摊 REFUND 行。
     * 空回滚由 TccParticipantTemplate 分支级保护（无 Try 日志不执行，绝不凭空反向补贴）。幂等 by (COUPON_RELEASE, key)。
     */
    public CouponUseDTO release(String couponId, String paymentId, String accountId, long amount, String operator) {
        fundService.requireEnabled();
        MarketingSupport.require(MarketingSupport.notBlank(couponId) && MarketingSupport.notBlank(paymentId), "couponId/paymentId 必填");
        String key = couponId + ":" + paymentId;
        String bizNo = MarketingSupport.FUND_REFUND_PREFIX + "C:" + key;
        MarketingSupport.requireKey(bizNo);
        return idempotentTemplate.execute("COUPON_RELEASE", key, CouponUseDTO.class, () -> {
            CouponDTO coupon = get(couponId);
            long now = System.currentTimeMillis();
            if (STATUS_FROZEN.equals(coupon.getStatus())) {
                int cas = couponMapper.casRelease(couponId, now);
                if (cas != 1) {
                    throw new BizException(ResultCode.CONFLICT, "券预占态并发变更，释放未受理: " + couponId);
                }
                support.audit("COUPON", couponId, "RELEASE", STATUS_FROZEN, STATUS_AVAILABLE, amount, bizNo,
                        operator, "TCC 释放预占 payment=" + paymentId);
            } else if (!STATUS_AVAILABLE.equals(coupon.getStatus())) {
                throw new BizException(ResultCode.CONFLICT, "券非预占/可用态，不可释放: " + coupon.getStatus());
            }
            // P-7 正确模型：Cancel 本地释放预占 + REFUND 分摊（抵消 Try 已提交的 USE）。
            // 模板保护 release 仅在 Try 成功（USE 已提交）时执行；补贴在 Confirm，Cancel 时必未落，故不反向补贴。
            fundService.recordSource(paymentId, SOURCE, couponId, amount, MarketingFundService.TYPE_REFUND, bizNo);
            CouponUseDTO dto = new CouponUseDTO();
            dto.setVoucherId(couponId);
            dto.setPaymentId(paymentId);
            dto.setAccountId(accountId);
            dto.setAmount(amount);
            dto.setBizNo(bizNo);
            dto.setStatus(STATUS_AVAILABLE);
            return dto;
        });
    }

    private long validateUsable(CouponDTO coupon, CouponUseRequest request) {
        if (!STATUS_AVAILABLE.equals(coupon.getStatus()) && !STATUS_FROZEN.equals(coupon.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "券状态不可核销: " + coupon.getStatus());
        }
        long now = System.currentTimeMillis();
        if (coupon.getExpireAt() <= now) {
            throw new BizException(ResultCode.BIZ_ERROR, "券已过期，不可核销: " + coupon.getId());
        }
        if (MarketingSupport.notBlank(request.getMemberId()) && !request.getMemberId().equals(coupon.getMemberId())) {
            throw new BizException(ResultCode.PARAM_ERROR, "券归属会员与请求不一致");
        }
        long amount = request.getAmount() != null ? request.getAmount() : coupon.getAmount();
        MarketingSupport.require(amount > 0 && amount <= coupon.getAmount(),
                "核销额需在 (0, 券面额] 内：amount=" + amount + " face=" + coupon.getAmount());
        if (request.getOrderAmount() != null) {
            MarketingSupport.require(request.getOrderAmount() >= coupon.getThreshold(),
                    "未达券门槛：orderAmount=" + request.getOrderAmount() + " threshold=" + coupon.getThreshold());
        }
        return amount;
    }

    private CouponUseDTO frozenDto(CouponDTO coupon, CouponUseRequest request, String accountId, long amount, String bizNo) {
        CouponUseDTO use = new CouponUseDTO();
        use.setKind(SOURCE);
        use.setVoucherId(coupon.getId());
        use.setPaymentId(request.getPaymentId());
        use.setOrderId(request.getOrderId());
        use.setMemberId(coupon.getMemberId());
        use.setAccountId(accountId);
        use.setAmount(amount);
        use.setBizNo(bizNo);
        use.setStatus(STATUS_FROZEN);
        use.setRequestId(request.getRequestId());
        use.setOperator(request.getOperator());
        long now = System.currentTimeMillis();
        use.setCreatedAt(now);
        use.setUpdatedAt(now);
        return use;
    }

    // ---------------- 退回 ----------------

    public CouponUseDTO refund(String couponUseId) {
        fundService.requireEnabled();
        MarketingSupport.require(MarketingSupport.notBlank(couponUseId), "couponUseId 必填");
        MarketingSupport.requireKey(couponUseId);
        return idempotentTemplate.execute("COUPON_REFUND", couponUseId, CouponUseDTO.class, () -> {
            CouponUseDTO use = useMapper.selectById(couponUseId);
            if (use == null) {
                // P-7 TCC：payment 营销腿 useId 存券 id（核销单 Confirm 异步生成），支持按券 id 回退查核销单。
                use = useMapper.selectByCoupon(couponUseId);
            }
            MarketingSupport.requireFound(use, "核销单不存在: " + couponUseId);
            if (STATUS_REFUNDED.equals(use.getStatus())) {
                return use;
            }
            if (!STATUS_USED.equals(use.getStatus())) {
                throw new BizException(ResultCode.CONFLICT, "核销单状态不可退回: " + use.getStatus());
            }
            long now = System.currentTimeMillis();
            String bizNo = MarketingSupport.FUND_REFUND_PREFIX + "C:" + use.getVoucherId() + ":" + use.getPaymentId();
            MarketingSupport.requireKey(bizNo);
            int cas = useMapper.casRefund(use.getId(), now);
            if (cas != 1) {
                throw new BizException(ResultCode.CONFLICT, "核销单并发变更，退回未受理: " + couponUseId);
            }
            fundService.subsidyBack(bizNo, use.getAccountId(), use.getAmount(), "营销券退回", use.getPaymentId());
            couponMapper.casRefund(use.getVoucherId(), now);
            fundService.recordSource(use.getPaymentId(), SOURCE, use.getId(), use.getAmount(),
                    MarketingFundService.TYPE_REFUND, bizNo);
            support.audit("COUPON", use.getVoucherId(), "REFUND", STATUS_USED, STATUS_REFUNDED, use.getAmount(),
                    bizNo, use.getOperator(), "退回核销单 " + couponUseId + "，资金反向 D 用户户/C SYS_MARKETING");
            return useMapper.selectById(couponUseId);
        });
    }

    // ---------------- 过期 ----------------

    public int expireDue() {
        long now = System.currentTimeMillis();
        int rows = couponMapper.expireDue(now);
        if (rows > 0) {
            support.audit("COUPON", "BATCH", "EXPIRE", STATUS_AVAILABLE, STATUS_EXPIRED, (long) rows,
                    "EXPIRE:" + now, "SYSTEM", "过期作业置 EXPIRED " + rows + " 张");
        }
        return rows;
    }
}