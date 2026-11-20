package com.qoder.payment.payment.facade;

import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.review.ReviewDTO;
import com.qoder.payment.facade.review.ReviewFacade;
import com.qoder.payment.payment.service.AmountReviewService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

/** S-7 大额双人复核 Dubbo 边界（FacadeSupport 统一异常转 Result）。 */
@DubboService
@RequiredArgsConstructor
public class ReviewFacadeImpl implements ReviewFacade {

    private final AmountReviewService amountReviewService;

    @Override
    public Result<Boolean> requiresReview(long amount) {
        return FacadeSupport.execute(() -> amountReviewService.requiresReview(amount));
    }

    @Override
    public Result<ReviewDTO> submitReview(String bizType, String bizNo, long amount, String submitter) {
        return FacadeSupport.execute(() -> amountReviewService.submit(bizType, bizNo, amount, submitter));
    }

    @Override
    public Result<ReviewDTO> approve(String reviewId, String approver) {
        return FacadeSupport.execute(() -> amountReviewService.approve(reviewId, approver));
    }

    @Override
    public Result<ReviewDTO> reject(String reviewId, String reason) {
        return FacadeSupport.execute(() -> amountReviewService.reject(reviewId, reason));
    }

    @Override
    public Result<List<ReviewDTO>> list(String status) {
        return FacadeSupport.execute(() -> amountReviewService.list(status));
    }

    @Override
    public Result<ReviewDTO> findByBiz(String bizType, String bizNo) {
        return FacadeSupport.execute(() -> amountReviewService.findByBiz(bizType, bizNo));
    }
}