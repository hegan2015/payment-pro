package com.qoder.payment.payment.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.payment.facade.review.ReviewDTO;
import com.qoder.payment.payment.config.ReviewProperties;
import com.qoder.payment.payment.dataobject.ReviewDO;
import com.qoder.payment.payment.mapper.ReviewMapper;
import com.qoder.tcc.IdempotentTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * S-7 大额双人复核状态机（资金防线）。
 *
 * <p>幂等键 (REVIEW,bizType:bizNo)：IdempotentTemplate 占位 + amount_review UNIQUE(biz_type,biz_no)
 * 双护栏，重复提交不重复建单。状态翻转只经 PENDING 的 CAS（并发审批只翻一次）。
 * 双人原则：approver 与 submitter 同人拒绝。复核单落 payment 库（H2/MySQL），宕机重启不丢。
 *
 * <p>作用域（§0.7 兼容红线）：闸门由网关 /api/** 拦截器执行（未复核绝不动账，A0428），
 * approve 后才放行原 TCC 动账流程；/openapi/** 商户路径不经过闸门，语义逐字节不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AmountReviewService {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    private final ReviewMapper reviewMapper;
    private final ReviewProperties properties;
    private final IdempotentTemplate idempotentTemplate;

    public boolean requiresReview(long amount) {
        return properties.isEnabled() && amount >= properties.getThreshold();
    }

    public ReviewDTO submit(String bizType, String bizNo, long amount, String submitter) {
        if (bizType == null || bizType.isBlank() || bizNo == null || bizNo.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "bizType/bizNo 必填");
        }
        MoneyUtil.requireValid(amount);
        String operator = submitter == null || submitter.isBlank() ? "console" : submitter;
        String key = bizType + ":" + bizNo;
        idempotentTemplate.execute("REVIEW", key, ReviewDTO.class, () -> {
            ReviewDO existing = reviewMapper.selectByBiz(bizType, bizNo);
            if (existing != null) {
                return toDTO(existing);
            }
            ReviewDO review = new ReviewDO();
            review.setId("AR" + reviewMapper.nextId());
            review.setBizType(bizType);
            review.setBizNo(bizNo);
            review.setAmount(amount);
            review.setStatus(PENDING);
            review.setSubmitter(operator);
            review.setCreatedAt(System.currentTimeMillis());
            try {
                reviewMapper.insert(review);
            } catch (DuplicateKeyException e) {
                log.info("复核单幂等命中 bizType={} bizNo={}", bizType, bizNo);
                return toDTO(reviewMapper.selectByBiz(bizType, bizNo));
            }
            MetricsSupport.count("review_submit", "biz", bizType);
            log.info("大额复核建单 reviewId={} bizType={} bizNo={} amount={} submitter={}",
                    review.getId(), bizType, bizNo, amount, operator);
            return toDTO(review);
        });
        // 返回实时状态（幂等缓存的是建单时快照，审批后重放须看到终态）
        return toDTO(reviewMapper.selectByBiz(bizType, bizNo));
    }

    public ReviewDTO approve(String reviewId, String approver) {
        if (approver == null || approver.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "approver 必填（双人复核第二人）");
        }
        ReviewDO review = require(reviewId);
        if (!PENDING.equals(review.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "复核单已终态: " + review.getStatus());
        }
        if (approver.equals(review.getSubmitter())) {
            MetricsSupport.count("review_dual_person_reject", "biz", review.getBizType());
            log.warn("双人复核拒绝：同人提交+审批 reviewId={} operator={}", reviewId, approver);
            throw new BizException(ResultCode.BIZ_ERROR, "双人复核：提交人与审批人不得同人: " + approver);
        }
        int flipped = reviewMapper.casApprove(reviewId, approver, System.currentTimeMillis());
        if (flipped == 0) {
            throw new BizException(ResultCode.CONFLICT, "复核单已被并发审批");
        }
        MetricsSupport.count("review_approve", "biz", review.getBizType());
        log.info("大额复核通过 reviewId={} bizType={} bizNo={} amount={} submitter={} approver={}",
                reviewId, review.getBizType(), review.getBizNo(), review.getAmount(), review.getSubmitter(), approver);
        return toDTO(require(reviewId));
    }

    public ReviewDTO reject(String reviewId, String reason) {
        ReviewDO review = require(reviewId);
        if (!PENDING.equals(review.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "复核单已终态: " + review.getStatus());
        }
        String why = reason == null || reason.isBlank() ? "复核拒绝" : reason;
        int flipped = reviewMapper.casReject(reviewId, why, System.currentTimeMillis());
        if (flipped == 0) {
            throw new BizException(ResultCode.CONFLICT, "复核单已被并发审批");
        }
        MetricsSupport.count("review_reject", "biz", review.getBizType());
        log.info("大额复核拒绝 reviewId={} bizType={} bizNo={} reason={}", reviewId, review.getBizType(), review.getBizNo(), why);
        return toDTO(require(reviewId));
    }

    public List<ReviewDTO> list(String status) {
        return reviewMapper.selectByStatus(status == null || status.isBlank() ? null : status)
                .stream().map(AmountReviewService::toDTO).toList();
    }

    public ReviewDTO findByBiz(String bizType, String bizNo) {
        ReviewDO review = reviewMapper.selectByBiz(bizType, bizNo);
        return review == null ? null : toDTO(review);
    }

    private ReviewDO require(String reviewId) {
        ReviewDO review = reviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BizException(ResultCode.NOT_FOUND, "复核单不存在: " + reviewId);
        }
        return review;
    }

    private static ReviewDTO toDTO(ReviewDO review) {
        ReviewDTO dto = new ReviewDTO();
        dto.setId(review.getId());
        dto.setBizType(review.getBizType());
        dto.setBizNo(review.getBizNo());
        dto.setAmount(review.getAmount());
        dto.setStatus(review.getStatus());
        dto.setSubmitter(review.getSubmitter());
        dto.setApprover(review.getApprover());
        dto.setRejectReason(review.getRejectReason());
        dto.setCreatedAt(review.getCreatedAt());
        dto.setReviewedAt(review.getReviewedAt());
        return dto;
    }
}