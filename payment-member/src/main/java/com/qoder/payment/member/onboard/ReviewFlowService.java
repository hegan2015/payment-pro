package com.qoder.payment.member.onboard;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.auth.AuditWriteRequest;
import com.qoder.payment.facade.member.MerchantApplicationDTO;
import com.qoder.payment.facade.member.ReviewTaskDTO;
import com.qoder.payment.member.dataobject.MerchantApplicationDO;
import com.qoder.payment.member.dataobject.ReviewTaskDO;
import com.qoder.payment.member.mapper.MerchantApplicationMapper;
import com.qoder.payment.member.mapper.ReviewTaskMapper;
import com.qoder.payment.member.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * X-2 进件审核流：SUBMITTED→PENDING 任务→approve/reject/supplement（补件）。
 * <ul>
 *   <li>审核权限 OPERATOR+（网关控制器校验权限点，reviewer 恒取会话用户防伪造）；</li>
 *   <li>任务完成 CAS（WHERE status='PENDING'）防并发双审；意见/审核人/时间全程留痕；</li>
 *   <li>approve→M-4 创建商户会员；reject/supplement→可重提（历史任务不改写）。</li>
 * </ul>
 * 大额/特殊资质双人复核（S-7 联动）为后续接线项（可复用 /api/reviews step-up 机制）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewFlowService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final MerchantApplicationMapper applicationMapper;
    private final MerchantOnboardService onboardService;
    private final AuditService auditService;

    public List<ReviewTaskDTO> listTasks(String status) {
        List<ReviewTaskDO> tasks = status == null || status.isBlank()
                ? reviewTaskMapper.selectAll() : reviewTaskMapper.selectByStatus(status.trim().toUpperCase());
        return tasks.stream().map(ReviewFlowService::toDTO).toList();
    }

    public List<ReviewTaskDTO> listByApplication(String applicationId) {
        return reviewTaskMapper.selectByApplication(applicationId).stream().map(ReviewFlowService::toDTO).toList();
    }

    @Transactional
    public MerchantApplicationDTO approve(String applicationId, String reviewer, String opinion) {
        ReviewTaskDO task = requirePending(applicationId);
        completeTask(task, reviewer, "APPROVED", opinion);
        MerchantApplicationDTO dto = onboardService.onApproved(applicationId, reviewer);
        audit("REVIEW_APPROVE", applicationId, "APPROVED", reviewer, opinion);
        return dto;
    }

    @Transactional
    public MerchantApplicationDTO reject(String applicationId, String reviewer, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "驳回必须填写原因");
        }
        ReviewTaskDO task = requirePending(applicationId);
        completeTask(task, reviewer, "REJECTED", reason);
        MerchantApplicationDO app = applicationMapper.selectById(applicationId);
        long now = System.currentTimeMillis();
        app.setStatus("REJECTED");
        app.setRejectReason(reason);
        app.setReviewedAt(now);
        app.setUpdatedAt(now);
        applicationMapper.updateReview(app);
        audit("REVIEW_REJECT", applicationId, "REJECTED", reviewer, reason);
        log.info("进件驳回 applicationId={} reviewer={}", applicationId, reviewer);
        return onboardService.get(applicationId);
    }

    @Transactional
    public MerchantApplicationDTO supplement(String applicationId, String reviewer, String opinion) {
        if (opinion == null || opinion.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "补件必须填写补件要求");
        }
        ReviewTaskDO task = requirePending(applicationId);
        completeTask(task, reviewer, "SUPPLEMENT", opinion);
        MerchantApplicationDO app = applicationMapper.selectById(applicationId);
        long now = System.currentTimeMillis();
        app.setStatus("SUPPLEMENT");
        app.setRejectReason(opinion);
        app.setReviewedAt(now);
        app.setUpdatedAt(now);
        applicationMapper.updateReview(app);
        audit("REVIEW_SUPPLEMENT", applicationId, "SUPPLEMENT", reviewer, opinion);
        return onboardService.get(applicationId);
    }

    private ReviewTaskDO requirePending(String applicationId) {
        MerchantApplicationDO app = applicationMapper.selectById(applicationId);
        if (app == null) {
            throw new BizException(ResultCode.NOT_FOUND, "进件单不存在: " + applicationId);
        }
        if (!"SUBMITTED".equals(app.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "仅 SUBMITTED 可审核，当前: " + app.getStatus());
        }
        ReviewTaskDO task = reviewTaskMapper.selectPendingByApplication(applicationId);
        if (task == null) {
            throw new BizException(ResultCode.CONFLICT, "无待审核任务: " + applicationId);
        }
        return task;
    }

    private void completeTask(ReviewTaskDO task, String reviewer, String status, String opinion) {
        if (reviewer == null || reviewer.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "reviewer 必填（取会话用户）");
        }
        task.setReviewer(reviewer);
        task.setStatus(status);
        task.setOpinion(opinion);
        task.setReviewedAt(System.currentTimeMillis());
        if (reviewTaskMapper.complete(task) == 0) {
            throw new BizException(ResultCode.CONFLICT, "审核任务已被处理（并发双审拒绝）: " + task.getId());
        }
    }

    private void audit(String action, String bizNo, String result, String reviewer, String opinion) {
        AuditWriteRequest audit = new AuditWriteRequest();
        audit.setUsername(reviewer);
        audit.setAction(action);
        audit.setBizNo(bizNo);
        audit.setResult(result);
        audit.setDetail(opinion);
        auditService.write(audit);
    }

    private static ReviewTaskDTO toDTO(ReviewTaskDO task) {
        ReviewTaskDTO dto = new ReviewTaskDTO();
        dto.setId(task.getId());
        dto.setApplicationId(task.getApplicationId());
        dto.setReviewer(task.getReviewer());
        dto.setStatus(task.getStatus());
        dto.setOpinion(task.getOpinion());
        dto.setCreatedAt(task.getCreatedAt());
        dto.setReviewedAt(task.getReviewedAt());
        return dto;
    }
}