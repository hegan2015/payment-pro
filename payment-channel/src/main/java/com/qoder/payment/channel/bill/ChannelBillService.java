package com.qoder.payment.channel.bill;

import com.qoder.payment.channel.config.ChannelConfigKeys;
import com.qoder.payment.channel.dataobject.ChannelBillDO;
import com.qoder.payment.channel.dataobject.ChannelBillDetailDO;
import com.qoder.payment.channel.dataobject.ChannelTradeDO;
import com.qoder.payment.channel.mapper.ChannelBillDetailMapper;
import com.qoder.payment.channel.mapper.ChannelBillMapper;
import com.qoder.payment.channel.mapper.ChannelTradeMapper;
import com.qoder.payment.channel.service.ChannelDisputeReporter;
import com.qoder.payment.channel.service.ChannelLimitService;
import com.qoder.payment.channel.spi.ChannelAdapter;
import com.qoder.payment.channel.spi.ChannelAdapterRegistry;
import com.qoder.payment.channel.spi.ChannelBillFile;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.channel.ChannelBillDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CH-4 渠道对账文件：下载（适配器生成/拉取）→ 落盘 → 解析（CSV）→ 汇总完整性校验 →
 * 与本地渠道流水<b>逐笔比对</b> → 差异经 {@link ChannelDisputeReporter} 接差错平台（source=PAYMENT）。
 *
 * <p><b>不改 recon 域任何代码</b>：只调 {@code DisputeFacade.create}（幂等去重），
 * 差错策略一律 MANUAL（账单差异不得自动冲正/动账，留人工核对，资金安全保守）。
 *
 * <p>幂等：UNIQUE(channel_code,biz_date) 一账单一行；UNIQUE(bill_id,channel_order_no) 明细去重，
 * 重复下载不重复插行（命中唯一键则刷新为最新文件内容），历史行标记 present=0 而非删除。
 *
 * <p>比对截止时点：只把「下载时刻之前」的本地流水纳入账单缺失判定（账单是时点快照，
 * 下载后新发生的流水不属于本账单期），避免把正常新增流水误报为差异。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelBillService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final ChannelBillMapper billMapper;
    private final ChannelBillDetailMapper detailMapper;
    private final ChannelTradeMapper tradeMapper;
    private final ChannelAdapterRegistry registry;
    private final List<BillParser> parsers;
    private final ChannelDisputeReporter disputeReporter;
    private final DynamicConfig config;

    /** 下载 + 解析 + 逐笔比对（幂等，可重复调用）。 */
    public ChannelBillDTO download(String channelCode, String bizDate) {
        String day = normalizeDate(bizDate);
        ChannelAdapter adapter = registry.resolve(channelCode);
        if (!adapter.billSupported()) {
            throw new BizException(ResultCode.PARAM_ERROR, "渠道不支持对账文件下载: " + channelCode);
        }
        long now = System.currentTimeMillis();
        ChannelBillDO bill = billMapper.selectByCodeDate(channelCode, day);
        if (bill == null) {
            bill = new ChannelBillDO();
            bill.setChannelCode(channelCode);
            bill.setBizDate(day);
            bill.setStatus("DOWNLOADED");
            bill.setCreatedAt(now);
            bill.setTotalCount(0L);
            bill.setTotalAmount(0L);
            bill.setParsedCount(0L);
            bill.setParsedAmount(0L);
            bill.setMatchedCount(0L);
            bill.setDiffCount(0L);
            bill.setDisputeCount(0L);
            billMapper.insert(bill);
        }
        ChannelBillFile file;
        try {
            file = adapter.downloadBill(day);
        } catch (Exception e) {
            bill.setStatus("FAILED");
            bill.setMessage("下载失败: " + e.getMessage());
            billMapper.updateDownloaded(bill);
            disputeReporter.report(ChannelDisputeReporter.CHECK_BILL, day, "SOURCE_UNAVAILABLE",
                    channelCode + ":" + day, null, 0L, "MANUAL",
                    "渠道对账文件下载失败（" + channelCode + " " + day + "）: " + e.getMessage());
            MetricsSupport.count("channel_bill", "channel", channelCode, "status", "download_failed");
            log.error("[告警] 渠道账单下载失败 channelCode={} bizDate={}: {}", channelCode, day, e.getMessage());
            return toDTO(bill, true);
        }
        Path path = persist(file);
        bill.setFilePath(path.toString());
        BillParser parser = pickParser(file.getFormat());
        BillParser.Parsed parsed;
        try {
            parsed = parser.parse(file.getContent());
        } catch (Exception e) {
            bill.setStatus("FAILED");
            bill.setMessage("解析失败: " + e.getMessage());
            billMapper.updateDownloaded(bill);
            disputeReporter.report(ChannelDisputeReporter.CHECK_BILL, day, "SOURCE_UNAVAILABLE",
                    channelCode + ":" + day, null, 0L, "MANUAL",
                    "渠道对账文件解析失败（" + channelCode + " " + day + "）: " + e.getMessage());
            MetricsSupport.count("channel_bill", "channel", channelCode, "status", "parse_failed");
            return toDTO(bill, true);
        }
        long sum = parsed.sumAmount();
        bill.setTotalCount(file.getDeclaredCount() == null ? (long) parsed.rows().size() : file.getDeclaredCount());
        bill.setTotalAmount(file.getDeclaredAmount() == null ? sum : file.getDeclaredAmount());
        bill.setParsedCount((long) parsed.rows().size());
        bill.setParsedAmount(sum);
        // 汇总完整性校验：声明汇总必须等于明细汇总（防截断/漏行）
        if (bill.getTotalCount() != parsed.rows().size() || bill.getTotalAmount() != sum) {
            bill.setStatus("FAILED");
            bill.setMessage("汇总不符: 声明 " + bill.getTotalCount() + " 笔/" + bill.getTotalAmount()
                    + " 分，明细 " + parsed.rows().size() + " 笔/" + sum + " 分");
            bill.setParsedAt(System.currentTimeMillis());
            billMapper.updateParsed(bill);
            disputeReporter.report(ChannelDisputeReporter.CHECK_BILL, day, "AMOUNT_MISMATCH",
                    channelCode + ":" + day, null, Math.abs(bill.getTotalAmount() - sum), "MANUAL",
                    "渠道账单汇总与明细不符（" + channelCode + " " + day + "）: " + bill.getMessage());
            MetricsSupport.count("channel_bill", "channel", channelCode, "status", "sum_mismatch");
            return toDTO(bill, true);
        }
        detailMapper.markAbsent(bill.getId());
        for (BillParser.Row row : parsed.rows()) {
            upsertDetail(bill.getId(), row);
        }
        bill.setStatus("PARSED");
        bill.setMessage("解析完成 " + parsed.rows().size() + " 笔 / " + sum + " 分（格式 " + parser.format() + "）");
        bill.setParsedAt(System.currentTimeMillis());
        billMapper.updateParsed(bill);
        MetricsSupport.count("channel_bill", "channel", channelCode, "status", "parsed");
        log.info("CH-4 账单下载解析完成 channelCode={} bizDate={} count={} amount={} file={}",
                channelCode, day, parsed.rows().size(), sum, path);
        return reconcile(bill.getId());
    }

    /** 逐笔比对（幂等：差错平台按 source+checkName+bizNo 去重，重复比对不重复建单）。 */
    public ChannelBillDTO reconcile(long billId) {
        ChannelBillDO bill = require(billId);
        List<ChannelBillDetailDO> details = detailMapper.selectPresent(billId);
        long[] range = dayRange(bill.getBizDate());
        List<ChannelTradeDO> locals = new ArrayList<>();
        for (ChannelTradeDO t : tradeMapper.selectTerminalInRange(range[0], range[1])) {
            if (belongsHere(t, bill.getChannelCode())) {
                locals.add(t);
            }
        }
        Map<String, ChannelTradeDO> byBizNo = new HashMap<>();
        for (ChannelTradeDO t : locals) {
            byBizNo.put(t.getBizNo(), t);
        }
        Set<String> billed = new HashSet<>();
        long matched = 0;
        long diff = 0;
        long disputes = 0;
        List<ChannelBillDTO.Diff> diffs = new ArrayList<>();
        for (ChannelBillDetailDO d : details) {
            billed.add(d.getBizNo());
            ChannelTradeDO local = byBizNo.get(d.getBizNo());
            String result;
            String disputeType = null;
            String reason = null;
            if (local == null) {
                result = "LOCAL_MISSING";
                disputeType = "ONE_SIDED";
                reason = "渠道账单有单、平台渠道流水缺失（单边账）: channelOrderNo=" + d.getChannelOrderNo()
                        + " amount=" + d.getAmount() + " status=" + d.getStatus();
            } else if (!java.util.Objects.equals(local.getAmount(), d.getAmount())) {
                result = "AMOUNT_DIFF";
                disputeType = "AMOUNT_MISMATCH";
                reason = "渠道账单金额与平台流水不符: 账单 " + d.getAmount() + " 分 vs 平台 " + local.getAmount() + " 分";
            } else if (!java.util.Objects.equals(local.getStatus(), d.getStatus())) {
                result = "STATUS_DIFF";
                disputeType = "STATUS_MISMATCH";
                reason = "渠道账单状态与平台流水不符: 账单 " + d.getStatus() + " vs 平台 " + local.getStatus();
            } else if (!java.util.Objects.equals(local.getType(), d.getType())) {
                result = "TYPE_DIFF";
                disputeType = "STATUS_MISMATCH";
                reason = "渠道账单交易类型与平台流水不符: 账单 " + d.getType() + " vs 平台 " + local.getType();
            } else {
                result = "MATCH";
            }
            Long disputeId = null;
            if (disputeType != null) {
                diff++;
                disputeId = disputeReporter.report(ChannelDisputeReporter.CHECK_BILL, bill.getBizDate(), disputeType,
                        d.getBizNo(), paymentIdOf(local, d.getBizNo()), d.getAmount(), "MANUAL", reason);
                if (disputeId != null) {
                    disputes++;
                }
                diffs.add(diffOf(d, local, result, disputeId, reason));
                MetricsSupport.count("channel_bill_diff", "channel", bill.getChannelCode(), "type", result);
            } else {
                matched++;
            }
            detailMapper.updateMatch(d.getId(), result, disputeId);
            d.setMatchResult(result);
            d.setDisputeId(disputeId);
        }
        // 平台有流水、账单缺行（只判定「本次解析时点」之前的流水，账单是时点快照）
        long cutoff = bill.getParsedAt() != null ? bill.getParsedAt()
                : (bill.getCreatedAt() == null ? Long.MAX_VALUE : bill.getCreatedAt());
        for (ChannelTradeDO local : locals) {
            if (billed.contains(local.getBizNo())) {
                continue;
            }
            if (local.getCreatedAt() != null && local.getCreatedAt() > cutoff) {
                continue;
            }
            diff++;
            String reason = "平台渠道流水有单、渠道账单缺失（短款/漏账）: status=" + local.getStatus()
                    + " amount=" + local.getAmount();
            Long disputeId = disputeReporter.report(ChannelDisputeReporter.CHECK_BILL, bill.getBizDate(),
                    "CHANNEL_MISSING", local.getBizNo(), paymentIdOf(local, local.getBizNo()),
                    local.getAmount(), "MANUAL", reason);
            if (disputeId != null) {
                disputes++;
            }
            ChannelBillDTO.Diff item = new ChannelBillDTO.Diff();
            item.setBizNo(local.getBizNo());
            item.setChannelOrderNo(local.getChannelOrderNo());
            item.setDiffType("BILL_MISSING");
            item.setLocalAmount(local.getAmount());
            item.setLocalStatus(local.getStatus());
            item.setDisputeId(disputeId);
            item.setReason(reason);
            diffs.add(item);
            MetricsSupport.count("channel_bill_diff", "channel", bill.getChannelCode(), "type", "BILL_MISSING");
        }
        bill.setMatchedCount(matched);
        bill.setDiffCount(diff);
        bill.setDisputeCount(disputes);
        bill.setMessage("逐笔比对: 一致 " + matched + " 笔，差异 " + diff + " 笔，差错单 " + disputes + " 个");
        billMapper.updateReconciled(bill);
        log.info("CH-4 账单逐笔比对 billId={} channelCode={} bizDate={} matched={} diff={} disputes={}",
                billId, bill.getChannelCode(), bill.getBizDate(), matched, diff, disputes);
        ChannelBillDTO dto = toDTO(bill, true);
        dto.setDiffs(diffs);
        return dto;
    }

    public List<ChannelBillDTO> list(String channelCode, String bizDate) {
        List<ChannelBillDTO> out = new ArrayList<>();
        for (ChannelBillDO bill : billMapper.selectList(blankToNull(channelCode), blankToNull(bizDate))) {
            out.add(toDTO(bill, false));
        }
        return out;
    }

    public ChannelBillDTO detail(long billId) {
        ChannelBillDTO dto = toDTO(require(billId), true);
        List<ChannelBillDTO.Diff> diffs = new ArrayList<>();
        for (ChannelBillDetailDO d : detailMapper.selectPresent(billId)) {
            if (d.getMatchResult() != null && !"MATCH".equals(d.getMatchResult())) {
                ChannelBillDTO.Diff item = new ChannelBillDTO.Diff();
                item.setBizNo(d.getBizNo());
                item.setChannelOrderNo(d.getChannelOrderNo());
                item.setDiffType(d.getMatchResult());
                item.setBillAmount(d.getAmount());
                item.setBillStatus(d.getStatus());
                item.setDisputeId(d.getDisputeId());
                diffs.add(item);
            }
        }
        dto.setDiffs(diffs);
        return dto;
    }

    // ---------- 内部 ----------

    private void upsertDetail(long billId, BillParser.Row row) {
        ChannelBillDetailDO detail = new ChannelBillDetailDO();
        detail.setBillId(billId);
        detail.setChannelOrderNo(row.channelOrderNo());
        detail.setBizNo(row.bizNo());
        detail.setType(row.type());
        detail.setAmount(row.amount());
        detail.setStatus(row.status());
        detail.setRaw(row.raw());
        detail.setPresent(1);
        try {
            detailMapper.insert(detail);
        } catch (DuplicateKeyException e) {
            ChannelBillDetailDO existing = detailMapper.selectByOrderNo(billId, row.channelOrderNo());
            if (existing != null) {
                detail.setId(existing.getId());
                detailMapper.refresh(detail);
            }
        }
    }

    private Path persist(ChannelBillFile file) {
        try {
            Path dir = Path.of(config.get(ChannelConfigKeys.BILL_DIR, ChannelConfigKeys.DEFAULT_BILL_DIR),
                    file.getChannelCode());
            Files.createDirectories(dir);
            Path path = dir.resolve(file.getFileName());
            Files.writeString(path, file.getContent(), StandardCharsets.UTF_8);
            return path;
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "账单落盘失败: " + e.getMessage());
        }
    }

    private BillParser pickParser(String format) {
        for (BillParser p : parsers) {
            if (p.supports(format)) {
                return p;
            }
        }
        throw new BizException(ResultCode.PARAM_ERROR, "无可用账单解析器: " + format);
    }

    private ChannelBillDO require(long billId) {
        ChannelBillDO bill = billMapper.selectById(billId);
        if (bill == null) {
            throw new BizException(ResultCode.NOT_FOUND, "账单不存在: " + billId);
        }
        return bill;
    }

    private ChannelBillDTO toDTO(ChannelBillDO bill, boolean withDetails) {
        ChannelBillDTO dto = new ChannelBillDTO();
        org.springframework.beans.BeanUtils.copyProperties(bill, dto);
        if (withDetails) {
            List<ChannelBillDTO.Detail> details = new ArrayList<>();
            for (ChannelBillDetailDO d : detailMapper.selectPresent(bill.getId())) {
                ChannelBillDTO.Detail item = new ChannelBillDTO.Detail();
                org.springframework.beans.BeanUtils.copyProperties(d, item);
                details.add(item);
            }
            dto.setDetails(details);
        }
        return dto;
    }

    private static ChannelBillDTO.Diff diffOf(ChannelBillDetailDO d, ChannelTradeDO local, String type,
                                              Long disputeId, String reason) {
        ChannelBillDTO.Diff item = new ChannelBillDTO.Diff();
        item.setChannelOrderNo(d.getChannelOrderNo());
        item.setBizNo(d.getBizNo());
        item.setDiffType(type);
        item.setBillAmount(d.getAmount());
        item.setBillStatus(d.getStatus());
        item.setLocalAmount(local == null ? null : local.getAmount());
        item.setLocalStatus(local == null ? null : local.getStatus());
        item.setDisputeId(disputeId);
        item.setReason(reason);
        return item;
    }

    private static String paymentIdOf(ChannelTradeDO local, String fallback) {
        if (local == null) {
            return fallback;
        }
        return "REFUND".equals(local.getType()) && local.getOrigBizNo() != null ? local.getOrigBizNo() : local.getBizNo();
    }

    private static boolean belongsHere(ChannelTradeDO t, String channelCode) {
        String code = t.getChannelCode();
        return channelCode.equals(code) || (code == null && "MOCK_BANK".equals(channelCode));
    }

    private static long[] dayRange(String bizDate) {
        LocalDate day = LocalDate.parse(bizDate, DAY);
        long from = day.atStartOfDay(ZONE).toInstant().toEpochMilli();
        return new long[]{from, from + 86_400_000L - 1};
    }

    private static String normalizeDate(String bizDate) {
        if (bizDate == null || bizDate.isBlank()) {
            return ChannelLimitService.bizDate(System.currentTimeMillis());
        }
        String trimmed = bizDate.trim();
        try {
            return DAY.format(LocalDate.parse(trimmed, DAY));
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_ERROR, "bizDate 必须为 yyyyMMdd: " + bizDate);
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}