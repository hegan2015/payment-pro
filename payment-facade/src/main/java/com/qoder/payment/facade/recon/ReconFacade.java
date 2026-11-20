package com.qoder.payment.facade.recon;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.common.PageDTO;

import java.util.List;

/** 对账域：跨服务核对（账务试算平衡、支付-账务-交易-清结算四方一致）。对标支付宝对账中心。 */
public interface ReconFacade {

    Result<ReconReportDTO> run();

    Result<ReconReportDTO> latest();

    // ==================================================================
    // P1 第二期 · J 泳道对账工作流（RC-2/RC-3/RC-4/RC-5）——以下全部 additive，
    // 既有 run()/latest() 签名/语义/六项判定口径一字未改（ride §0.7 红线）。
    // ==================================================================

    // ---------------- RC-2 日切批量对账 ----------------

    /**
     * 按会计日批量对账（六项口径与实时一致，数据范围按 payment.created_at 归日）。
     * bizDate 为空 = accounting 当前会计日（账面日，不硬编码当前日期）。
     * 幂等：同会计日重跑复用同一批次行（attempt 递增），差错单按
     * (DISPUTE,RECON:checkName:bizDate:bizNo) 去重不重复建单。默认不自动跑批，
     * 由 XXL-Job reconDaily / 控制台 / ppjob 触发。
     */
    Result<ReconReportDTO> runDaily(Long bizDate);

    // ---------------- RC-3 渠道内外对账 ----------------

    /**
     * 内部渠道流水 vs 外部渠道对账文件逐笔比对（金额/状态/手续费），
     * 长款/短款/金额不符/状态不符分类建差错单并给处置建议。
     * CH-4 账单不可用时退化 queryChannelSide 渠道侧视图（source=QUERY_FALLBACK 如实标注）。
     * 幂等：UNIQUE(channel_code,biz_date) 重跑复用同一行。bizDate 为空 = 当前会计日。
     */
    Result<ChannelReconResultDTO> runChannelRecon(String channelCode, Long bizDate);

    /** 渠道内外对账差异明细（channelCode/bizDate 均可空=全量）。 */
    Result<List<ChannelReconDiffDTO>> channelDiffs(String channelCode, Long bizDate);

    // ---------------- RC-4 报表 / 进度 ----------------

    /** 对账报告分页（元数据，不含 checks 正文；bizDate/reconType 可空=全量，跨片归并不重不漏）。 */
    Result<PageDTO<ReconReportDTO>> listReports(Long bizDate, String reconType, int page, int size);

    /** 单报告全文（含六项 checks 明细）。 */
    Result<ReconReportDTO> getReport(String reportId);

    /** 批次列表（recon_batch，bizDate/reconType 可空=全量）。 */
    Result<List<ReconBatchDTO>> listBatches(Long bizDate, String reconType);

    /** 分片进度汇总（各片 checked 累加=total，Java 归并）。reconType 空=DAILY。 */
    Result<ReconProgressDTO> getProgress(Long bizDate, String reconType);

    /** 对账汇总报表（按日/渠道/商户/差异类型，Java 归并跨片安全）。bizDate 可空=全量。 */
    Result<ReconSummaryDTO> summary(Long bizDate);

    /** 差错处理时效报表（建单→分派→修复→核销→关闭时长）。bizDate 会计日 epoch millis，可空=全部。 */
    Result<DisputeSlaDTO> disputeSla(Long bizDate);

    /** 对账报告 CSV 导出。 */
    Result<String> reportsCsv(Long bizDate, String reconType);

    /** 差错时效 CSV 导出。 */
    Result<String> disputeSlaCsv(Long bizDate);

    /** 渠道内外对账差异 CSV 导出。 */
    Result<String> channelDiffsCsv(String channelCode, Long bizDate);
}