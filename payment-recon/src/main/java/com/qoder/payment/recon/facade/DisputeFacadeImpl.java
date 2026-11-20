package com.qoder.payment.recon.facade;

import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.common.PageDTO;
import com.qoder.payment.facade.dispute.DisputeAutoFixResultDTO;
import com.qoder.payment.facade.dispute.DisputeCreateRequest;
import com.qoder.payment.facade.dispute.DisputeDTO;
import com.qoder.payment.facade.dispute.DisputeFacade;
import com.qoder.payment.facade.dispute.DisputeQuery;
import com.qoder.payment.facade.dispute.DisputeStatsDTO;
import com.qoder.payment.recon.service.DisputeAutoFixer;
import com.qoder.payment.recon.service.DisputeService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

/** X-1 差错处理平台 Dubbo 边界（归 recon 域，不新增进程）。 */
@DubboService
@RequiredArgsConstructor
public class DisputeFacadeImpl implements DisputeFacade {

    private final DisputeService disputeService;
    private final DisputeAutoFixer disputeAutoFixer;

    @Override
    public Result<DisputeDTO> create(DisputeCreateRequest request) {
        return FacadeSupport.execute(() -> disputeService.create(request));
    }

    @Override
    public Result<PageDTO<DisputeDTO>> list(DisputeQuery query) {
        return FacadeSupport.execute(() -> disputeService.list(query == null ? new DisputeQuery() : query));
    }

    @Override
    public Result<DisputeDTO> get(long disputeId) {
        return FacadeSupport.execute(() -> disputeService.get(disputeId));
    }

    @Override
    public Result<DisputeDTO> assign(long disputeId, String assignee, String operator) {
        return FacadeSupport.execute(() -> disputeService.assign(disputeId, assignee, operator));
    }

    @Override
    public Result<DisputeDTO> approve(long disputeId, String operator) {
        return FacadeSupport.execute(() -> disputeAutoFixer.fix(disputeId, operator));
    }

    @Override
    public Result<DisputeDTO> reject(long disputeId, String operator, String reason) {
        return FacadeSupport.execute(() -> disputeService.reject(disputeId, operator, reason));
    }

    @Override
    public Result<DisputeDTO> resolve(long disputeId, String operator, String resolution) {
        return FacadeSupport.execute(() -> disputeService.resolve(disputeId, operator, resolution));
    }

    @Override
    public Result<DisputeDTO> close(long disputeId, String operator) {
        return FacadeSupport.execute(() -> disputeService.close(disputeId, operator));
    }

    @Override
    public Result<DisputeDTO> autoFix(long disputeId) {
        return FacadeSupport.execute(() -> disputeAutoFixer.fix(disputeId, "autofix"));
    }

    @Override
    public Result<DisputeAutoFixResultDTO> autoFixRun(Boolean execute) {
        return FacadeSupport.execute(() -> disputeAutoFixer.runAll(execute, "autofix-run"));
    }

    @Override
    public Result<DisputeDTO> resolveBySourceBiz(String source, String bizNo, String operator, String detail) {
        return FacadeSupport.execute(() -> disputeService.resolveBySourceBiz(source, bizNo, operator, detail));
    }

    @Override
    public Result<DisputeStatsDTO> stats() {
        return FacadeSupport.execute(disputeService::stats);
    }
}