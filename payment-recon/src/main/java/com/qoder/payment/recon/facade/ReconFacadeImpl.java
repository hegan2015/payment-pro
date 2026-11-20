package com.qoder.payment.recon.facade;

import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.common.PageDTO;
import com.qoder.payment.facade.recon.ChannelReconDiffDTO;
import com.qoder.payment.facade.recon.ChannelReconResultDTO;
import com.qoder.payment.facade.recon.DisputeSlaDTO;
import com.qoder.payment.facade.recon.ReconBatchDTO;
import com.qoder.payment.facade.recon.ReconFacade;
import com.qoder.payment.facade.recon.ReconProgressDTO;
import com.qoder.payment.facade.recon.ReconReportDTO;
import com.qoder.payment.facade.recon.ReconSummaryDTO;
import com.qoder.payment.recon.service.ChannelReconService;
import com.qoder.payment.recon.service.DailyReconService;
import com.qoder.payment.recon.service.ReconReportService;
import com.qoder.payment.recon.service.ReconService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
@RequiredArgsConstructor
public class ReconFacadeImpl implements ReconFacade {

    private final ReconService reconService;
    private final DailyReconService dailyReconService;
    private final ChannelReconService channelReconService;
    private final ReconReportService reconReportService;

    @Override
    public Result<ReconReportDTO> run() {
        return FacadeSupport.execute(reconService::run);
    }

    @Override
    public Result<ReconReportDTO> latest() {
        return FacadeSupport.execute(reconService::latest);
    }

    // ---------------- RC-2 日切批量对账（additive） ----------------

    @Override
    public Result<ReconReportDTO> runDaily(Long bizDate) {
        return FacadeSupport.execute(() -> dailyReconService.runDaily(bizDate));
    }

    // ---------------- RC-3 渠道内外对账（additive） ----------------

    @Override
    public Result<ChannelReconResultDTO> runChannelRecon(String channelCode, Long bizDate) {
        return FacadeSupport.execute(() -> channelReconService.runChannelRecon(channelCode, bizDate));
    }

    @Override
    public Result<List<ChannelReconDiffDTO>> channelDiffs(String channelCode, Long bizDate) {
        return FacadeSupport.execute(() -> reconReportService.channelDiffs(channelCode, bizDate));
    }

    // ---------------- RC-4 报表 / 进度（additive） ----------------

    @Override
    public Result<PageDTO<ReconReportDTO>> listReports(Long bizDate, String reconType, int page, int size) {
        return FacadeSupport.execute(() -> {
            int p = Math.max(1, page);
            int s = Math.min(200, Math.max(1, size));
            Long day = bizDate == null ? null : ReconService.dayOf(bizDate);
            List<ReconReportDTO> items = reconService.reportsMetaPage(day, reconType, (p - 1) * s, s);
            long total = reconService.reportsMetaCount(day, reconType);
            return PageDTO.of(items, p, s, total);
        });
    }

    @Override
    public Result<ReconReportDTO> getReport(String reportId) {
        return FacadeSupport.execute(() -> reconService.getReport(reportId));
    }

    @Override
    public Result<List<ReconBatchDTO>> listBatches(Long bizDate, String reconType) {
        return FacadeSupport.execute(() -> reconReportService.batches(bizDate, reconType));
    }

    @Override
    public Result<ReconProgressDTO> getProgress(Long bizDate, String reconType) {
        return FacadeSupport.execute(() -> reconReportService.progress(bizDate, reconType));
    }

    @Override
    public Result<ReconSummaryDTO> summary(Long bizDate) {
        return FacadeSupport.execute(() -> reconReportService.summary(bizDate));
    }

    @Override
    public Result<DisputeSlaDTO> disputeSla(Long bizDate) {
        return FacadeSupport.execute(() -> reconReportService.disputeSla(bizDate));
    }

    @Override
    public Result<String> reportsCsv(Long bizDate, String reconType) {
        return FacadeSupport.execute(() -> reconReportService.reportsCsv(bizDate, reconType));
    }

    @Override
    public Result<String> disputeSlaCsv(Long bizDate) {
        return FacadeSupport.execute(() -> reconReportService.disputeSlaCsv(bizDate));
    }

    @Override
    public Result<String> channelDiffsCsv(String channelCode, Long bizDate) {
        return FacadeSupport.execute(() -> reconReportService.channelDiffsCsv(channelCode, bizDate));
    }
}