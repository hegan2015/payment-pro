package com.qoder.payment.marketing.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.marketing.ActivityApplyRequest;
import com.qoder.payment.facade.marketing.ActivityDTO;
import com.qoder.payment.facade.marketing.ActivityUseDTO;
import com.qoder.payment.marketing.mapper.ActivityMapper;
import com.qoder.payment.marketing.mapper.ActivityUseMapper;
import com.qoder.tcc.IdempotentTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * X-3b 活动核销（立减 DISCOUNT / 满减 FULL_REDUCE）。
 *
 * <p><b>预算并发安全</b>：{@code UPDATE activity SET used=used+? WHERE used+?<=budget AND status='ACTIVE'}
 * 原子条件更新（H2/MySQL 同构），跨实例并发不超扣——等价且强于「local 内存锁 / docker Redis 计数」，
 * 且无新增中间件依赖（local 零依赖红线）。退回时 {@code used>=amount} 才回滚，绝不为负。
 *
 * <p><b>幂等键</b>：{@code (ACTIVITY_APPLY, activityId:paymentId)} + {@code activity_use}
 * UNIQUE(activity_id,payment_id) + UNIQUE(request_id)；退回 {@code (ACTIVITY_REFUND, useId)}。
 *
 * <p><b>资金</b>：立减额即营销补贴，{@code D SYS_MARKETING / C 用户户}（accounting 复式记账）。
 * 规则匹配只用 threshold/discountBps/discountAmount/maxDiscount 确定性字段，不引入表达式引擎，
 * 避免误配置放大资金风险（§5.5）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityService {

    public static final String SOURCE = "ACTIVITY";
    public static final String TYPE_DISCOUNT = "DISCOUNT";
    public static final String TYPE_FULL_REDUCE = "FULL_REDUCE";

    private final ActivityMapper activityMapper;
    private final ActivityUseMapper useMapper;
    private final MarketingSupport support;
    private final MarketingFundService fundService;
    private final IdempotentTemplate idempotentTemplate;

    public ActivityDTO save(ActivityDTO activity) {
        MarketingSupport.require(activity != null, "活动必填");
        MarketingSupport.require(MarketingSupport.notBlank(activity.getName()), "name 必填");
        MarketingSupport.require(TYPE_DISCOUNT.equals(activity.getType()) || TYPE_FULL_REDUCE.equals(activity.getType()),
                "type 必须为 DISCOUNT 或 FULL_REDUCE");
        long threshold = MarketingSupport.nz(activity.getThreshold());
        long budget = MarketingSupport.nz(activity.getBudget());
        MarketingSupport.require(threshold >= 0 && budget >= 0, "threshold/budget 不得为负");
        if (TYPE_FULL_REDUCE.equals(activity.getType())) {
            MarketingSupport.require(MarketingSupport.nz(activity.getDiscountAmount()) > 0,
                    "FULL_REDUCE 需 discountAmount > 0");
        } else {
            int bps = MarketingSupport.nz(activity.getDiscountBps());
            MarketingSupport.require(bps > 0 && bps <= 10000, "DISCOUNT 需 discountBps ∈ (0,10000]");
        }
        String status = MarketingSupport.notBlank(activity.getStatus()) ? activity.getStatus() : "ACTIVE";
        MarketingSupport.require("ACTIVE".equals(status) || "DISABLED".equals(status), "status 必须为 ACTIVE/DISABLED");
        long now = System.currentTimeMillis();
        String key = MarketingSupport.notBlank(activity.getId()) ? activity.getId() : activity.getName();
        MarketingSupport.requireKey(key);
        return idempotentTemplate.execute("ACTIVITY_SAVE", key, ActivityDTO.class, () -> {
            ActivityDTO existing = MarketingSupport.notBlank(activity.getId())
                    ? activityMapper.selectById(activity.getId()) : null;
            if (existing != null) {
                existing.setName(activity.getName());
                existing.setType(activity.getType());
                existing.setThreshold(threshold);
                existing.setDiscountAmount(MarketingSupport.nz(activity.getDiscountAmount()));
                existing.setDiscountBps(MarketingSupport.nz(activity.getDiscountBps()));
                existing.setMaxDiscount(MarketingSupport.nz(activity.getMaxDiscount()));
                existing.setBudget(budget);
                existing.setStatus(status);
                existing.setExpireAt(activity.getExpireAt());
                existing.setRule(activity.getRule());
                existing.setOperator(activity.getOperator());
                existing.setUpdatedAt(now);
                activityMapper.update(existing);
                support.audit("ACTIVITY", existing.getId(), "UPDATE", null, status, budget, existing.getId(),
                        activity.getOperator(), "活动更新（used 只经原子扣减/回滚，保持 " + existing.getUsed() + "）");
                return activityMapper.selectById(existing.getId());
            }
            ActivityDTO row = new ActivityDTO();
            row.setId(MarketingSupport.notBlank(activity.getId()) ? activity.getId() : support.newId("AC"));
            row.setName(activity.getName());
            row.setType(activity.getType());
            row.setThreshold(threshold);
            row.setDiscountAmount(MarketingSupport.nz(activity.getDiscountAmount()));
            row.setDiscountBps(MarketingSupport.nz(activity.getDiscountBps()));
            row.setMaxDiscount(MarketingSupport.nz(activity.getMaxDiscount()));
            row.setBudget(budget);
            row.setUsed(0L);
            row.setStatus(status);
            row.setExpireAt(activity.getExpireAt());
            row.setRule(activity.getRule());
            row.setOperator(activity.getOperator());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            activityMapper.insert(row);
            support.audit("ACTIVITY", row.getId(), "CREATE", null, status, budget, row.getId(),
                    activity.getOperator(), "活动新建 type=" + row.getType() + " budget=" + budget);
            return row;
        });
    }

    public ActivityDTO get(String activityId) {
        ActivityDTO activity = activityMapper.selectById(activityId);
        MarketingSupport.requireFound(activity, "活动不存在: " + activityId);
        return activity;
    }

    public List<ActivityDTO> list(Long orderAmount) {
        return activityMapper.selectActive(System.currentTimeMillis(), orderAmount);
    }

    /** 立减额计算（确定性、向下取整、封顶；绝不超过订单额）。 */
    public long discountOf(ActivityDTO activity, long orderAmount) {
        long amount;
        if (TYPE_FULL_REDUCE.equals(activity.getType())) {
            amount = MarketingSupport.nz(activity.getDiscountAmount());
        } else {
            amount = orderAmount * MarketingSupport.nz(activity.getDiscountBps()) / 10000L;
            long cap = MarketingSupport.nz(activity.getMaxDiscount());
            if (cap > 0 && amount > cap) {
                amount = cap;
            }
        }
        return Math.min(amount, orderAmount);
    }

    public ActivityUseDTO apply(ActivityApplyRequest request) {
        fundService.requireEnabled();
        MarketingSupport.require(request != null, "请求必填");
        MarketingSupport.require(MarketingSupport.notBlank(request.getActivityId()), "activityId 必填");
        MarketingSupport.require(MarketingSupport.notBlank(request.getPaymentId()), "paymentId 必填");
        MarketingSupport.require(request.getOrderAmount() != null && request.getOrderAmount() > 0,
                "orderAmount 必须为正整数（分）");
        String key = request.getActivityId() + ":" + request.getPaymentId();
        MarketingSupport.requireKey(key);
        String requestId = MarketingSupport.notBlank(request.getRequestId()) ? request.getRequestId() : "AU:" + key;
        MarketingSupport.requireKey(requestId);
        String bizNo = MarketingSupport.FUND_PREFIX + "A:" + key;
        MarketingSupport.requireKey(bizNo);
        long orderAmount = request.getOrderAmount();
        return idempotentTemplate.execute("ACTIVITY_APPLY", key, ActivityUseDTO.class, () -> {
            ActivityUseDTO byRequest = useMapper.selectByRequest(requestId);
            if (byRequest != null) {
                return byRequest;
            }
            ActivityUseDTO byKey = useMapper.selectByKey(request.getActivityId(), request.getPaymentId());
            if (byKey != null) {
                return byKey;
            }
            ActivityDTO activity = get(request.getActivityId());
            long now = System.currentTimeMillis();
            if (!"ACTIVE".equals(activity.getStatus())) {
                throw new BizException(ResultCode.BIZ_ERROR, "活动非进行中: " + activity.getStatus());
            }
            if (activity.getExpireAt() != null && activity.getExpireAt() <= now) {
                throw new BizException(ResultCode.BIZ_ERROR, "活动已过期: " + activity.getId());
            }
            if (orderAmount < MarketingSupport.nz(activity.getThreshold())) {
                throw new BizException(ResultCode.BIZ_ERROR, "未达活动门槛：orderAmount=" + orderAmount
                        + " threshold=" + activity.getThreshold());
            }
            long amount = discountOf(activity, orderAmount);
            MarketingSupport.requireBiz(amount > 0, "活动立减额为 0，不受理核销");
            long already = fundService.sourceNet(request.getPaymentId());
            MarketingSupport.require(already + amount <= orderAmount,
                    "营销抵扣超订单额：已抵扣 " + already + " + 本次 " + amount + " > " + orderAmount);
            String memberId = MarketingSupport.notBlank(request.getMemberId()) ? request.getMemberId() : "UNKNOWN";
            String accountId = fundService.resolveAccount(request.getMemberId(), request.getAccountId());
            // 预算原子抢占（不超扣），失败即整体回滚，绝不动账
            int budget = activityMapper.consumeBudget(activity.getId(), amount, now);
            if (budget != 1) {
                throw new BizException(ResultCode.BIZ_ERROR, "活动预算不足或状态已变更: " + activity.getId());
            }
            fundService.subsidy(bizNo, accountId, amount, "营销活动立减", request.getPaymentId());

            ActivityUseDTO use = new ActivityUseDTO();
            use.setId(support.newId("AU"));
            use.setActivityId(activity.getId());
            use.setPaymentId(request.getPaymentId());
            use.setOrderId(request.getOrderId());
            use.setMemberId(memberId);
            use.setAccountId(accountId);
            use.setAmount(amount);
            use.setOrderAmount(orderAmount);
            use.setBizNo(bizNo);
            use.setStatus(CouponService.STATUS_USED);
            use.setRequestId(requestId);
            use.setOperator(request.getOperator());
            use.setCreatedAt(now);
            use.setUpdatedAt(now);
            useMapper.insert(use);
            fundService.recordSource(request.getPaymentId(), SOURCE, use.getId(), amount,
                    MarketingFundService.TYPE_USE, bizNo);
            support.audit("ACTIVITY", activity.getId(), "USE", null, CouponService.STATUS_USED, amount, bizNo,
                    request.getOperator(), "活动核销 payment=" + request.getPaymentId() + " orderAmount="
                            + orderAmount + " budgetUsed=" + (MarketingSupport.nz(activity.getUsed()) + amount));
            return use;
        });
    }

    // ---------------- TCC 三阶段（P-7 支付宝式） ----------------

    /** TCC Try：抢占活动预算 + 预补贴 + 来源分摊 USE 行。幂等 by (ACTIVITY_RESERVE, key)。 */
    public ActivityUseDTO reserve(ActivityApplyRequest request) {
        fundService.requireEnabled();
        MarketingSupport.require(request != null && MarketingSupport.notBlank(request.getActivityId()), "activityId 必填");
        MarketingSupport.require(MarketingSupport.notBlank(request.getPaymentId()), "paymentId 必填");
        MarketingSupport.require(request.getOrderAmount() != null && request.getOrderAmount() > 0, "orderAmount 必须为正整数（分）");
        String key = request.getActivityId() + ":" + request.getPaymentId();
        String bizNo = MarketingSupport.FUND_PREFIX + "A:" + key;
        MarketingSupport.requireKey(bizNo);
        long orderAmount = request.getOrderAmount();
        return idempotentTemplate.execute("ACTIVITY_RESERVE", key, ActivityUseDTO.class, () -> {
            ActivityDTO activity = get(request.getActivityId());
            long amount = validateActive(activity, orderAmount);
            String memberId = MarketingSupport.notBlank(request.getMemberId()) ? request.getMemberId() : "UNKNOWN";
            String accountId = fundService.resolveAccount(request.getMemberId(), request.getAccountId());
            long now = System.currentTimeMillis();
            int budget = activityMapper.consumeBudget(activity.getId(), amount, now);
            if (budget != 1) {
                throw new BizException(ResultCode.BIZ_ERROR, "活动预算不足或状态已变更: " + activity.getId());
            }
            // P-7 正确模型：Try 本地抢占预算 + USE 分摊（随模板事务提交），远程补贴在 Confirm。
            fundService.recordSource(request.getPaymentId(), SOURCE, activity.getId(), amount,
                    MarketingFundService.TYPE_USE, bizNo);
            support.audit("ACTIVITY", activity.getId(), "RESERVE", null, CouponService.STATUS_FROZEN, amount, bizNo,
                    request.getOperator(), "TCC 预占 payment=" + request.getPaymentId());
            return frozenDto(activity, request, memberId, accountId, amount, orderAmount, bizNo);
        });
    }

    /** TCC Confirm：落核销单（预算与预补贴、USE 分摊已在 Try）。幂等 by (ACTIVITY_APPLY, key)。 */
    public ActivityUseDTO commit(ActivityApplyRequest request) {
        fundService.requireEnabled();
        String key = request.getActivityId() + ":" + request.getPaymentId();
        String bizNo = MarketingSupport.FUND_PREFIX + "A:" + key;
        long orderAmount = request.getOrderAmount();
        return idempotentTemplate.execute("ACTIVITY_APPLY", key, ActivityUseDTO.class, () -> {
            ActivityUseDTO byKey = useMapper.selectByKey(request.getActivityId(), request.getPaymentId());
            if (byKey != null) {
                return byKey;
            }
            ActivityDTO activity = get(request.getActivityId());
            long amount = discountOf(activity, orderAmount);
            MarketingSupport.requireBiz(amount > 0, "活动立减额为 0，不受理核销");
            String memberId = MarketingSupport.notBlank(request.getMemberId()) ? request.getMemberId() : "UNKNOWN";
            String accountId = fundService.resolveAccount(request.getMemberId(), request.getAccountId());
            ActivityUseDTO use = frozenDto(activity, request, memberId, accountId, amount, orderAmount, bizNo);
            use.setId(support.newId("AU"));
            use.setStatus(CouponService.STATUS_USED);
            useMapper.insert(use);
            support.audit("ACTIVITY", activity.getId(), "USE", CouponService.STATUS_FROZEN, CouponService.STATUS_USED, amount,
                    bizNo, request.getOperator(), "TCC 核销 payment=" + request.getPaymentId());
            return use;
        });
    }

    /** TCC Cancel：回滚预算 + 反向补贴 + 来源分摊 REFUND 行。空回滚由模板保护。幂等 by (ACTIVITY_RELEASE, key)。 */
    public ActivityUseDTO release(String activityId, String paymentId, String accountId, long amount, String operator) {
        fundService.requireEnabled();
        MarketingSupport.require(MarketingSupport.notBlank(activityId) && MarketingSupport.notBlank(paymentId), "activityId/paymentId 必填");
        String key = activityId + ":" + paymentId;
        String bizNo = MarketingSupport.FUND_REFUND_PREFIX + "A:" + key;
        MarketingSupport.requireKey(bizNo);
        return idempotentTemplate.execute("ACTIVITY_RELEASE", key, ActivityUseDTO.class, () -> {
            long now = System.currentTimeMillis();
            activityMapper.releaseBudget(activityId, amount, now);
            // P-7 正确模型：Cancel 本地回滚预算 + REFUND 分摊（抵消 Try USE）；补贴在 Confirm，Cancel 时未落不反向。
            fundService.recordSource(paymentId, SOURCE, activityId, amount, MarketingFundService.TYPE_REFUND, bizNo);
            support.audit("ACTIVITY", activityId, "RELEASE", CouponService.STATUS_FROZEN, null, amount, bizNo,
                    operator, "TCC 释放预占 payment=" + paymentId);
            ActivityUseDTO dto = new ActivityUseDTO();
            dto.setActivityId(activityId);
            dto.setPaymentId(paymentId);
            dto.setAccountId(accountId);
            dto.setAmount(amount);
            dto.setBizNo(bizNo);
            return dto;
        });
    }

    private long validateActive(ActivityDTO activity, long orderAmount) {
        long now = System.currentTimeMillis();
        if (!"ACTIVE".equals(activity.getStatus())) {
            throw new BizException(ResultCode.BIZ_ERROR, "活动非进行中: " + activity.getStatus());
        }
        if (activity.getExpireAt() != null && activity.getExpireAt() <= now) {
            throw new BizException(ResultCode.BIZ_ERROR, "活动已过期: " + activity.getId());
        }
        if (orderAmount < MarketingSupport.nz(activity.getThreshold())) {
            throw new BizException(ResultCode.BIZ_ERROR, "未达活动门槛：orderAmount=" + orderAmount
                    + " threshold=" + activity.getThreshold());
        }
        long amount = discountOf(activity, orderAmount);
        MarketingSupport.requireBiz(amount > 0, "活动立减额为 0，不受理核销");
        return amount;
    }

    private ActivityUseDTO frozenDto(ActivityDTO activity, ActivityApplyRequest request, String memberId,
                                     String accountId, long amount, long orderAmount, String bizNo) {
        ActivityUseDTO use = new ActivityUseDTO();
        use.setActivityId(activity.getId());
        use.setPaymentId(request.getPaymentId());
        use.setOrderId(request.getOrderId());
        use.setMemberId(memberId);
        use.setAccountId(accountId);
        use.setAmount(amount);
        use.setOrderAmount(orderAmount);
        use.setBizNo(bizNo);
        use.setStatus(CouponService.STATUS_FROZEN);
        use.setRequestId(request.getRequestId());
        use.setOperator(request.getOperator());
        long now = System.currentTimeMillis();
        use.setCreatedAt(now);
        use.setUpdatedAt(now);
        return use;
    }

    public ActivityUseDTO refund(String activityUseId) {
        fundService.requireEnabled();
        MarketingSupport.require(MarketingSupport.notBlank(activityUseId), "activityUseId 必填");
        MarketingSupport.requireKey(activityUseId);
        return idempotentTemplate.execute("ACTIVITY_REFUND", activityUseId, ActivityUseDTO.class, () -> {
            ActivityUseDTO use = useMapper.selectById(activityUseId);
            if (use == null) {
                use = useMapper.selectByActivity(activityUseId);
            }
            MarketingSupport.requireFound(use, "活动核销单不存在: " + activityUseId);
            if (CouponService.STATUS_REFUNDED.equals(use.getStatus())) {
                return use;
            }
            if (!CouponService.STATUS_USED.equals(use.getStatus())) {
                throw new BizException(ResultCode.CONFLICT, "活动核销单状态不可退回: " + use.getStatus());
            }
            long now = System.currentTimeMillis();
            String bizNo = MarketingSupport.FUND_REFUND_PREFIX + "A:" + use.getActivityId() + ":" + use.getPaymentId();
            MarketingSupport.requireKey(bizNo);
            int cas = useMapper.casRefund(use.getId(), now);
            if (cas != 1) {
                throw new BizException(ResultCode.CONFLICT, "活动核销单并发变更，退回未受理: " + activityUseId);
            }
            fundService.subsidyBack(bizNo, use.getAccountId(), use.getAmount(), "营销活动退回", use.getPaymentId());
            int released = activityMapper.releaseBudget(use.getActivityId(), use.getAmount(), now);
            if (released != 1) {
                throw new BizException(ResultCode.CONFLICT, "活动预算回滚失败（used 不足），整体回滚: "
                        + use.getActivityId());
            }
            fundService.recordSource(use.getPaymentId(), SOURCE, use.getId(), use.getAmount(),
                    MarketingFundService.TYPE_REFUND, bizNo);
            support.audit("ACTIVITY", use.getActivityId(), "REFUND", CouponService.STATUS_USED,
                    CouponService.STATUS_REFUNDED, use.getAmount(), bizNo, use.getOperator(),
                    "活动退回 " + activityUseId + "，预算回滚 + 资金反向");
            return useMapper.selectById(activityUseId);
        });
    }
}