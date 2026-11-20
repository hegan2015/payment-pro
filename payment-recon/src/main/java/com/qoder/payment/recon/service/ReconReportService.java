package com.qoder.payment.recon.service;

import com.qoder.payment.facade.dispute.DisputeDTO;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.facade.payment.PaymentFacade;
import com.qoder.payment.facade.recon.ChannelReconDiffDTO;
import com.qoder.payment.facade.recon.DisputeSlaDTO;
import com.qoder.payment.facade.recon.ReconBatchDTO;
import com.qoder.payment.facade.recon.ReconProgressDTO;
import com.qoder.payment.facade.recon.ReconReportDTO;
import com.qoder.payment.facade.recon.ReconSummaryDTO;
import com.qoder.payment.recon.dataobject.ChannelReconDiffDO;
import com.qoder.payment.recon.dataobject.ChannelReconRunDO;
import com.qoder.payment.recon.dataobject.DisputeFixLogDO;
import com.qoder.payment.recon.dataobject.DisputeOrderDO;
import com.qoder.payment.recon.dataobject.ReconBatchDO;
import com.qoder.payment.recon.dataobject.ReconProgressDO;
import com.qoder.payment.recon.mapper.ChannelReconDiffMapper;
import com.qoder.payment.recon.mapper.ChannelReconRunMapper;
import com.qoder.payment.recon.mapper.DisputeFixLogMapper;
import com.qoder.payment.recon.mapper.DisputeOrderMapper;
import com.qoder.payment.recon.mapper.ReconBatchMapper;
import com.qoder.payment.recon.mapper.ReconProgressMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RC-4 对账/差错报表：汇总（按日/渠道/商户/差异类型）、差错处理时效（建单→分派→修复→核销→关闭）、
 * 进度汇总、CSV 导出。
 *
 * <p><b>跨片聚合归并红线（I-5a/b 踩坑）</b>：顶层 SUM 绝不藏进 COALESCE 等函数——
 * ShardingSphere 会当普通投影返回多行。本服务所有聚合一律取原始行后 <b>Java 归并</b>：
 * recon_report 只取元数据投影（无大字段、无 SQL 聚合），dispute/diff/batch/progress 均 SINGLE 表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconReportService {

    private final ReconService reconService;
    private final DailyReconService dailyReconService;
    private final ReconBatchMapper batchMapper;
    private final ReconProgressMapper progressMapper;
    private final ChannelReconRunMapper channelRunMapper;
    private final ChannelReconDiffMapper channelDiffMapper;
    private final DisputeOrderMapper disputeOrderMapper;
    private final DisputeFixLogMapper disputeFixLogMapper;

    @DubboReference
    private PaymentFacade paymentFacade;

    // ------------------------------------------------------------------ 进度 / 批次 / 差异查询

    /**
     * RC-5 各片进度 Java 归并：total/checked/disputeCount 累加，passed/failed=通过/失败片数。
     * 同一会计日存在多种分片配置（如先单片全量后 3 片广播）时，只归并<b>最新分片配置</b>
     * （shardTotal 最大的一组），避免跨配置重复累加。
     */
    public ReconProgressDTO progress(Long bizDate, String reconType) {
        long day = dailyReconService.resolveDay(bizDate);
        String type = reconType == null || reconType.isBlank() ? DailyReconService.TYPE_DAILY : reconType;
        List<ReconProgressDO> all = progressMapper.selectByDay(day, type);
        int maxTotal = all.stream().mapToInt(r -> r.getShardTotal() == null ? 1 : r.getShardTotal()).max().orElse(1);
        List<ReconProgressDO> rows = all.stream()
                .filter(r -> (r.getShardTotal() == null ? 1 : r.getShardTotal()) == maxTotal).toList();
        ReconProgressDTO dto = new ReconProgressDTO();
        dto.setBizDate(day);
        dto.setReconType(type);
        boolean anyFailed = false;
        boolean anyRunning = false;
        boolean allSuccess = !rows.isEmpty();
        for (ReconProgressDO row : rows) {
            dto.setTotal(dto.getTotal() + nz(row.getTotal()));
            dto.setChecked(dto.getChecked() + nz(row.getChecked()));
            dto.setPassed(dto.getPassed() + nz(row.getPassed()));
            dto.setFailed(dto.getFailed() + nz(row.getFailed()));
            dto.setDisputeCount(dto.getDisputeCount() + nz(row.getDisputeCount()));
            anyFailed |= "FAILED".equals(row.getStatus());
            anyRunning |= "RUNNING".equals(row.getStatus()) || "PENDING".equals(row.getStatus());
            allSuccess &= "SUCCESS".equals(row.getStatus());
            ReconProgressDTO.Shard shard = new ReconProgressDTO.Shard();
            shard.setShardIndex(row.getShardIndex());
            shard.setShardTotal(row.getShardTotal());
            shard.setTotal(row.getTotal());
            shard.setChecked(row.getChecked());
            shard.setPassed(row.getPassed());
            shard.setFailed(row.getFailed());
            shard.setDisputeCount(row.getDisputeCount());
            shard.setStatus(row.getStatus());
            shard.setUpdatedAt(row.getUpdatedAt());
            dto.getShards().add(shard);
        }
        dto.setPercent(dto.getTotal() == 0 ? 0 : dto.getChecked() * 100 / dto.getTotal());
        dto.setStatus(anyFailed ? "FAILED" : anyRunning ? "RUNNING" : allSuccess ? "SUCCESS" : "PENDING");
        return dto;
    }

    public List<ReconBatchDTO> batches(Long bizDate, String reconType) {
        Long day = bizDate == null ? null : ReconService.dayOf(bizDate);
        String type = reconType == null || reconType.isBlank() ? null : reconType;
        return batchMapper.selectList(day, type).stream().map(ReconReportService::toDTO).toList();
    }

    public List<ChannelReconDiffDTO> channelDiffs(String channelCode, Long bizDate) {
        String code = channelCode == null || channelCode.isBlank() ? null : channelCode;
        Long day = bizDate == null ? null : ReconService.dayOf(bizDate);
        return channelDiffMapper.selectList(code, day).stream().map(ChannelReconService::toDTO).toList();
    }

    // ------------------------------------------------------------------ 汇总报表

    public ReconSummaryDTO summary(Long bizDate) {
        Long day = bizDate == null ? null : ReconService.dayOf(bizDate);
        String dayText = day == null ? null : ReconService.dayText(day);
        ReconSummaryDTO dto = new ReconSummaryDTO();
        dto.setBizDate(day);

        // 按日：recon_report 元数据（REALTIME 按 checkedAt 归日，DAILY 按账面会计日）
        Map<String, ReconSummaryDTO.Row> byDay = new LinkedHashMap<>();
        for (ReconReportDTO report : reconService.allReportsMeta()) {
            long reportDay = report.getBizDate() != null ? report.getBizDate()
                    : ReconService.dayOf(report.getCheckedAt());
            if (day != null && reportDay != day) {
                continue;
            }
            ReconSummaryDTO.Row row = byDay.computeIfAbsent(ReconService.dayText(reportDay),
                    k -> ReconSummaryDTO.Row.of("day", k));
            row.setRuns(row.getRuns() + 1);
            if (Boolean.TRUE.equals(report.getPass())) {
                row.setPassed(row.getPassed() + 1);
            } else {
                row.setFailed(row.getFailed() + 1);
            }
            dto.setTotalRuns(dto.getTotalRuns() + 1);
            if (Boolean.TRUE.equals(report.getPass())) {
                dto.setPassedRuns(dto.getPassedRuns() + 1);
            } else {
                dto.setFailedRuns(dto.getFailedRuns() + 1);
            }
        }
        dto.setByDay(new ArrayList<>(byDay.values()));

        // 按渠道：channel_recon_run + channel_recon_diff
        Map<String, ReconSummaryDTO.Row> byChannel = new LinkedHashMap<>();
        for (ChannelReconRunDO run : channelRunMapper.selectList(null, day)) {
            ReconSummaryDTO.Row row = byChannel.computeIfAbsent(run.getChannelCode(),
                    k -> ReconSummaryDTO.Row.of("channel", k));
            row.setRuns(row.getRuns() + 1);
            if (Boolean.TRUE.equals(run.getPass())) {
                row.setPassed(row.getPassed() + 1);
            } else {
                row.setFailed(row.getFailed() + 1);
            }
        }
        Map<String, ReconSummaryDTO.Row> byDiffType = new LinkedHashMap<>();
        for (ChannelReconDiffDO diff : channelDiffMapper.selectList(null, day)) {
            ReconSummaryDTO.Row chRow = byChannel.computeIfAbsent(diff.getChannelCode(),
                    k -> ReconSummaryDTO.Row.of("channel", k));
            chRow.setDisputes(chRow.getDisputes() + (diff.getDisputeId() == null ? 0 : 1));
            chRow.setAmount(chRow.getAmount() + Math.abs(diff.getExternalAmount() != null
                    ? diff.getExternalAmount() : nz(diff.getInternalAmount())));
            ReconSummaryDTO.Row typeRow = byDiffType.computeIfAbsent(diff.getDiffType(),
                    k -> ReconSummaryDTO.Row.of("diffType", k));
            typeRow.setRuns(typeRow.getRuns() + 1);
            typeRow.setDisputes(typeRow.getDisputes() + (diff.getDisputeId() == null ? 0 : 1));
            typeRow.setAmount(typeRow.getAmount() + Math.abs(diff.getExternalAmount() != null
                    ? diff.getExternalAmount() : nz(diff.getInternalAmount())));
        }
        dto.setByChannel(new ArrayList<>(byChannel.values()));
        dto.setByDiffType(new ArrayList<>(byDiffType.values()));

        // 差错单（SINGLE 表，取原始行 Java 归并）
        List<DisputeOrderDO> disputes = allDisputes(dayText);
        dto.setDisputeCount(disputes.size());
        Map<String, ReconSummaryDTO.Row> disputeByType = new LinkedHashMap<>();
        Map<String, ReconSummaryDTO.Row> byMerchant = new LinkedHashMap<>();
        Map<String, String> merchantOf = merchantIndex();
        for (DisputeOrderDO dispute : disputes) {
            ReconSummaryDTO.Row typeRow = disputeByType.computeIfAbsent(dispute.getType(),
                    k -> ReconSummaryDTO.Row.of("disputeType", k));
            typeRow.setDisputes(typeRow.getDisputes() + 1);
            typeRow.setAmount(typeRow.getAmount() + nz(dispute.getAmount()));
            String merchant = dispute.getPaymentId() == null ? "UNKNOWN"
                    : merchantOf.getOrDefault(dispute.getPaymentId(), "UNKNOWN");
            ReconSummaryDTO.Row mRow = byMerchant.computeIfAbsent(merchant,
                    k -> ReconSummaryDTO.Row.of("merchant", k));
            mRow.setDisputes(mRow.getDisputes() + 1);
            mRow.setAmount(mRow.getAmount() + nz(dispute.getAmount()));
        }
        dto.setDisputeByType(new ArrayList<>(disputeByType.values()));
        dto.setByMerchant(new ArrayList<>(byMerchant.values()));
        return dto;
    }

    // ------------------------------------------------------------------ 差错时效报表

    public DisputeSlaDTO disputeSla(Long bizDate) {
        String dayText = bizDate == null ? null : ReconService.dayText(ReconService.dayOf(bizDate));
        DisputeSlaDTO dto = new DisputeSlaDTO();
        dto.setBizDate(dayText);
        List<DisputeOrderDO> disputes = allDisputes(dayText);
        dto.setTotal(disputes.size());
        Map<String, List<Long>> stageDurations = new LinkedHashMap<>();
        stageDurations.put("建单→分派", new ArrayList<>());
        stageDurations.put("分派→修复", new ArrayList<>());
        stageDurations.put("修复→核销", new ArrayList<>());
        stageDurations.put("核销→关闭", new ArrayList<>());
        stageDurations.put("建单→核销", new ArrayList<>());
        for (DisputeOrderDO order : disputes) {
            DisputeSlaDTO.Item item = new DisputeSlaDTO.Item();
            item.setId(order.getId());
            item.setDisputeNo(order.getDisputeNo());
            item.setType(order.getType());
            item.setSource(order.getSource());
            item.setStatus(order.getStatus());
            item.setBizDate(order.getBizDate());
            item.setCreatedAt(order.getCreatedAt());
            Long assignedAt = null;
            Long fixingAt = null;
            Long closedAt = null;
            for (DisputeFixLogDO fixLog : disputeFixLogMapper.selectByDispute(order.getId())) {
                if (assignedAt == null && "ASSIGNED".equals(fixLog.getAfterStatus())) {
                    assignedAt = fixLog.getCreatedAt();
                }
                if (fixingAt == null && "FIXING".equals(fixLog.getAfterStatus())) {
                    fixingAt = fixLog.getCreatedAt();
                }
                if (closedAt == null && "CLOSED".equals(fixLog.getAfterStatus())) {
                    closedAt = fixLog.getCreatedAt();
                }
            }
            item.setAssignedAt(assignedAt);
            item.setFixingAt(fixingAt);
            item.setResolvedAt(order.getResolvedAt());
            item.setClosedAt(closedAt);
            item.setCreateToAssignMs(duration(order.getCreatedAt(), assignedAt));
            item.setAssignToFixMs(duration(assignedAt, fixingAt));
            item.setFixToResolveMs(duration(fixingAt, order.getResolvedAt()));
            item.setResolveToCloseMs(duration(order.getResolvedAt(), closedAt));
            item.setCreateToResolveMs(duration(order.getCreatedAt(), order.getResolvedAt()));
            addDuration(stageDurations.get("建单→分派"), item.getCreateToAssignMs());
            addDuration(stageDurations.get("分派→修复"), item.getAssignToFixMs());
            addDuration(stageDurations.get("修复→核销"), item.getFixToResolveMs());
            addDuration(stageDurations.get("核销→关闭"), item.getResolveToCloseMs());
            addDuration(stageDurations.get("建单→核销"), item.getCreateToResolveMs());
            dto.getItems().add(item);
        }
        for (Map.Entry<String, List<Long>> entry : stageDurations.entrySet()) {
            DisputeSlaDTO.Stage stage = DisputeSlaDTO.Stage.of(entry.getKey());
            List<Long> values = entry.getValue();
            stage.setCount(values.size());
            stage.setAvgMs(values.isEmpty() ? 0 : values.stream().mapToLong(Long::longValue).sum() / values.size());
            stage.setMaxMs(values.stream().mapToLong(Long::longValue).max().orElse(0));
            dto.getStages().add(stage);
        }
        return dto;
    }

    // ------------------------------------------------------------------ CSV 导出

    public String reportsCsv(Long bizDate, String reconType) {
        Long day = bizDate == null ? null : ReconService.dayOf(bizDate);
        String type = reconType == null || reconType.isBlank() ? null : reconType;
        StringBuilder sb = new StringBuilder("id,checkedAt,pass,bizDate,reconType,channelCode\n");
        for (ReconReportDTO report : reconService.allReportsMeta()) {
            if (day != null) {
                long reportDay = report.getBizDate() != null ? report.getBizDate()
                        : ReconService.dayOf(report.getCheckedAt());
                if (reportDay != day) {
                    continue;
                }
            }
            if (type != null && !type.equals(report.getReconType())) {
                continue;
            }
            sb.append(csv(report.getId())).append(',')
                    .append(report.getCheckedAt()).append(',')
                    .append(report.getPass()).append(',')
                    .append(report.getBizDate() == null ? "" : report.getBizDate()).append(',')
                    .append(csv(report.getReconType())).append(',')
                    .append(csv(report.getChannelCode())).append('\n');
        }
        return sb.toString();
    }

    public String disputeSlaCsv(Long bizDate) {
        DisputeSlaDTO sla = disputeSla(bizDate);
        StringBuilder sb = new StringBuilder("disputeNo,type,source,status,bizDate,createdAt,assignedAt,fixingAt,"
                + "resolvedAt,closedAt,createToAssignMs,assignToFixMs,fixToResolveMs,resolveToCloseMs,createToResolveMs\n");
        for (DisputeSlaDTO.Item item : sla.getItems()) {
            sb.append(csv(item.getDisputeNo())).append(',')
                    .append(csv(item.getType())).append(',')
                    .append(csv(item.getSource())).append(',')
                    .append(csv(item.getStatus())).append(',')
                    .append(csv(item.getBizDate())).append(',')
                    .append(num(item.getCreatedAt())).append(',')
                    .append(num(item.getAssignedAt())).append(',')
                    .append(num(item.getFixingAt())).append(',')
                    .append(num(item.getResolvedAt())).append(',')
                    .append(num(item.getClosedAt())).append(',')
                    .append(num(item.getCreateToAssignMs())).append(',')
                    .append(num(item.getAssignToFixMs())).append(',')
                    .append(num(item.getFixToResolveMs())).append(',')
                    .append(num(item.getResolveToCloseMs())).append(',')
                    .append(num(item.getCreateToResolveMs())).append('\n');
        }
        return sb.toString();
    }

    public String channelDiffsCsv(String channelCode, Long bizDate) {
        StringBuilder sb = new StringBuilder("channelCode,bizDate,bizNo,channelOrderNo,diffType,internalAmount,"
                + "externalAmount,internalFee,externalFee,internalStatus,externalStatus,status,disputeId,suggestion\n");
        for (ChannelReconDiffDTO diff : channelDiffs(channelCode, bizDate)) {
            sb.append(csv(diff.getChannelCode())).append(',')
                    .append(diff.getBizDate()).append(',')
                    .append(csv(diff.getBizNo())).append(',')
                    .append(csv(diff.getChannelOrderNo())).append(',')
                    .append(csv(diff.getDiffType())).append(',')
                    .append(num(diff.getInternalAmount())).append(',')
                    .append(num(diff.getExternalAmount())).append(',')
                    .append(num(diff.getInternalFee())).append(',')
                    .append(num(diff.getExternalFee())).append(',')
                    .append(csv(diff.getInternalStatus())).append(',')
                    .append(csv(diff.getExternalStatus())).append(',')
                    .append(csv(diff.getStatus())).append(',')
                    .append(num(diff.getDisputeId())).append(',')
                    .append(csv(diff.getSuggestion())).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 内部

    private List<DisputeOrderDO> allDisputes(String dayText) {
        List<DisputeOrderDO> all = disputeOrderMapper.selectPage(null, null, null, null, 0, 100000);
        if (dayText == null) {
            return all;
        }
        return all.stream().filter(d -> dayText.equals(d.getBizDate())).toList();
    }

    private Map<String, String> merchantIndex() {
        Map<String, String> index = new HashMap<>();
        try {
            for (PaymentDTO payment : paymentFacade.listPayments().orThrow()) {
                if (payment.getMerchantAccountId() != null) {
                    index.put(payment.getId(), payment.getMerchantAccountId());
                }
            }
        } catch (Exception e) {
            log.warn("RC-4 商户维度归并：支付域不可达，商户维度降级 UNKNOWN: {}", e.getMessage());
        }
        return index;
    }

    private static void addDuration(List<Long> values, Long duration) {
        if (duration != null) {
            values.add(duration);
        }
    }

    private static Long duration(Long from, Long to) {
        return from == null || to == null || to < from ? null : to - from;
    }

    private static ReconBatchDTO toDTO(ReconBatchDO row) {
        ReconBatchDTO dto = new ReconBatchDTO();
        dto.setId(row.getId());
        dto.setBizDate(row.getBizDate());
        dto.setReconType(row.getReconType());
        dto.setChannelCode(row.getChannelCode());
        dto.setShardIndex(row.getShardIndex());
        dto.setShardTotal(row.getShardTotal());
        dto.setStatus(row.getStatus());
        dto.setAttempt(row.getAttempt());
        dto.setReportId(row.getReportId());
        dto.setTotal(row.getTotal());
        dto.setChecked(row.getChecked());
        dto.setPassed(row.getPassed());
        dto.setFailed(row.getFailed());
        dto.setDisputeCount(row.getDisputeCount());
        dto.setMessage(row.getMessage());
        dto.setCreatedAt(row.getCreatedAt());
        dto.setUpdatedAt(row.getUpdatedAt());
        return dto;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static long nz(Integer v) {
        return v == null ? 0L : v;
    }

    private static String num(Long v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** CSV 字段转义：含逗号/引号/换行时加引号，内部引号翻倍。 */
    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}