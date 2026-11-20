package com.qoder.payment.facade.review;

import com.qoder.payment.common.result.Result;

import java.util.List;

/**
 * S-7 大额双人复核（资金防线）。
 *
 * <p>作用域（ride-pro 兼容红线 §0.7）：复核闸门只作用于控制台 REST(/api/**) 发起的
 * 退款/冲正等大额动账；/openapi/** 商户资金单路径语义与同步行为逐字节不变。
 * 未复核（PENDING/REJECTED/未建单）绝不动账，拒绝错误码 A0428 / REVIEW_PENDING；
 * approve 后才放行原 TCC 动账流程。复核幂等键 (REVIEW,bizType:bizNo)。
 */
public interface ReviewFacade {

    /** 是否触发大额复核（阈值 payment.review.threshold，默认 1000000 分=1 万元，可配）。 */
    Result<Boolean> requiresReview(long amount);

    /** 提交复核单（(REVIEW,bizType:bizNo) 幂等，重复提交返回既有单不重复建单）。 */
    Result<ReviewDTO> submitReview(String bizType, String bizNo, long amount, String submitter);

    /** 审批通过：仅 PENDING 可翻 APPROVED（CAS）；approver 与 submitter 同人拒绝（双人）。 */
    Result<ReviewDTO> approve(String reviewId, String approver);

    /** 审批拒绝：仅 PENDING 可翻 REJECTED（CAS），留痕拒绝原因。 */
    Result<ReviewDTO> reject(String reviewId, String reason);

    /** 按状态过滤列表；status 为空返回全部。 */
    Result<List<ReviewDTO>> list(String status);

    /** 按业务键查复核单（闸门用）；不存在返回 data=null。 */
    Result<ReviewDTO> findByBiz(String bizType, String bizNo);
}