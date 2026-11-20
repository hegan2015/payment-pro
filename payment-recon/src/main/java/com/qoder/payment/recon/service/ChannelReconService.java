package com.qoder.payment.recon.service;

import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.facade.channel.ChannelBillDTO;
import com.qoder.payment.facade.channel.ChannelFacade;
import com.qoder.payment.facade.channel.ChannelTradeDTO;
import com.qoder.payment.facade.dispute.DisputeCreateRequest;
import com.qoder.payment.facade.dispute.DisputeDTO;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.facade.payment.PaymentFacade;
import com.qoder.payment.facade.recon.ChannelReconDiffDTO;
import com.qoder.payment.facade.recon.ChannelReconResultDTO;
import com.qoder.payment.facade.settle.SettleFacade;
import com.qoder.payment.facade.settle.SettleRecordDTO;
import com.qoder.payment.recon.dataobject.ChannelReconDiffDO;
import com.qoder.payment.recon.dataobject.ChannelReconRunDO;
import com.qoder.payment.recon.dataobject.ReconBatchDO;
import com.qoder.payment.recon.mapper.ChannelReconDiffMapper;
import com.qoder.payment.recon.mapper.ChannelReconRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * RC-3 渠道内外对账：内部渠道流水（channel_trade 经 ChannelFacade）vs 外部渠道对账文件
 * （CH-4 channel_bill_detail 经 ChannelFacade.downloadBill/listBills/getBillDetail）逐笔比对。
 *
 * <p>差异分类与处置建议（全部经 X-1 差错平台幂等建单，<b>不改他域代码、不自动动账</b>）：
 * <ul>
 *   <li>LONG 长款（外部账单有、内部流水无，渠道多扣）→ ONE_SIDED/SUSPENSE：挂账 SYS_SUSPENSE 待人工核实退汇/补单；</li>
 *   <li>SHORT 短款（内部有、账单无，渠道未扣/漏账）→ CHANNEL_MISSING/SUSPENSE：挂账待人工补扣，保守不臆断冲正；</li>
 *   <li>AMOUNT 金额不符 → AMOUNT_MISMATCH/MANUAL：人工核对方向后红冲蓝补（账单差异不自动冲正）；</li>
 *   <li>STATUS 状态/类型不符 → STATUS_MISMATCH/MANUAL；</li>
 *   <li>FEE 手续费不符 → FEE_MISMATCH/SUSPENSE（差额挂账）。</li>
 * </ul>
 *
 * <p><b>手续费口径如实披露</b>：CH-4 账单 CSV 格式当前无手续费列，外部手续费不可得 →
 * 手续费核对采用内部口径一致性（payment.fee vs 结算手续费，只读比对，手续费口径不变）；
 * 外部手续费列就绪后可直接接入内外比对（feeNote 标注）。
 *
 * <p><b>CH-4 未就绪降级</b>：账单下载/查询不可用时退化 {@code queryChannelSide}（渠道侧视图）
 * 逐笔回查，source=QUERY_FALLBACK 如实标注（模拟银行口径，不冒充外部账单）。
 *
 * <p>幂等：channel_recon_run UNIQUE(channel_code,biz_date) 重跑复用行；
 * channel_recon_diff UNIQUE(channel_code,biz_date,biz_no,diff_type) 重跑去重；
 * 差错单 (DISPUTE,RECON:渠道内外对账:bizDate:bizNo) 去重不重复建单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelReconService {

    public static final String TYPE_CHANNEL = "CHANNEL";
    public static final String CHECK_NAME = "渠道内外对账";
    private static final String DEFAULT_CHANNEL = "MOCK_BANK";
    private static final long DAY_MILLIS = 86_400_000L;

    private final ChannelReconRunMapper runMapper;
    private final ChannelReconDiffMapper diffMapper;
    private final com.qoder.payment.recon.mapper.ReconBatchMapper batchMapper;
    private final DisputeService disputeService;
    private final DailyReconService dailyReconService;

    @DubboReference
    private ChannelFacade channelFacade;

    @DubboReference
    private PaymentFacade paymentFacade;

    @DubboReference
    private SettleFacade settleFacade;

    public ChannelReconResultDTO runChannelRecon(String channelCode, Long bizDate) {
        String code = channelCode == null || channelCode.isBlank() ? DEFAULT_CHANNEL : channelCode.trim();
        long day = dailyReconService.resolveDay(bizDate);
        String dayText = ReconService.dayText(day);
        ReconBatchDO batch = dailyReconService.startBatch(day, TYPE_CHANNEL, code, 0, 1);
        ChannelReconResultDTO result = new ChannelReconResultDTO();
        result.setChannelCode(code);
        result.setBizDate(day);
        try {
            // ---------- 外部口径：CH-4 账单优先，不可用降级渠道侧查询 ----------
            // 每次重新下载（CH-4 幂等：UNIQUE(channel_code,biz_date) 复用行、明细刷新为最新文件），
            // 保证账单是最新时点快照；再取内部流水，配合 SHORT 判定的 parsedAt 截止时点不误报新增流水。
            ChannelBillDTO bill = null;
            String billError = null;
            try {
                bill = channelFacade.downloadBill(code, dayText).orThrow();
            } catch (Exception e) {
                billError = e.getMessage();
                log.warn("RC-3 CH-4 账单不可用，降级渠道侧查询口径 channel={} day={}: {}", code, dayText, e.getMessage());
            }

            // ---------- 内部渠道流水（当日终态，归属本渠道；历史无渠道码行归属模拟银行主渠道） ----------
            List<ChannelTradeDTO> internal = channelFacade.listTrades().orThrow().stream()
                    .filter(t -> belongsHere(t, code))
                    .filter(t -> "CAPTURED".equals(t.getStatus()) || "REFUNDED".equals(t.getStatus())
                            || "FAILED".equals(t.getStatus()))
                    .filter(t -> t.getCreatedAt() != null && t.getCreatedAt() >= day && t.getCreatedAt() < day + DAY_MILLIS)
                    .toList();
            result.setInternalCount(internal.size());
            result.setInternalTotal(internal.stream().mapToLong(t -> Math.abs(nz(t.getAmount()))).sum());

            List<ChannelReconDiffDO> diffs = new ArrayList<>();
            List<Long> disputeIds = new ArrayList<>();
            long matched = 0;
            long feeChecked = 0;
            if (bill != null && "PARSED".equals(bill.getStatus())) {
                result.setSource("BILL");
                result.setBillId(bill.getId());
                ChannelBillDTO full = channelFacade.getBillDetail(bill.getId()).orThrow();
                List<ChannelBillDTO.Detail> details = full.getDetails() == null ? List.of() : full.getDetails();
                result.setExternalCount(details.size());
                result.setExternalTotal(details.stream().mapToLong(d -> Math.abs(nz(d.getAmount()))).sum());
                matched = compareBill(code, day, dayText, internal, details, bill, diffs, disputeIds, result);
                feeChecked = checkInternalFee(code, day, dayText, internal, diffs, disputeIds);
                result.setFeeNote("CH-4 账单 CSV 无手续费列，外部手续费不可得；手续费核对采用内部口径一致性"
                        + "（payment.fee vs 结算手续费，只读，口径不变），共核 " + feeChecked + " 笔。");
            } else if (bill != null) {
                // 账单完整性失败（下载/解析/汇总不符）：不逐笔比对，建单留痕
                result.setSource("BILL");
                result.setBillId(bill.getId());
                result.setExternalTotal(nz(bill.getTotalAmount()));
                String reason = "渠道账单完整性失败（" + code + " " + dayText + "）: " + bill.getMessage();
                DisputeDTO dispute = createDispute(code, dayText, DisputeClassifier.AMOUNT_MISMATCH,
                        code + ":" + dayText, null, Math.abs(result.getExternalTotal() - result.getInternalTotal()),
                        DisputeClassifier.MANUAL, reason, "账单完整性失败：人工核实账单文件后重新下载比对");
                disputeIds.add(dispute.getId());
                diffs.add(persistDiff(code, day, code + ":" + dayText, null, "AMOUNT",
                        result.getInternalTotal(), result.getExternalTotal(), null, null, null, null,
                        dispute, "账单完整性失败：人工核实账单文件后重新下载比对"));
                result.setFeeNote("账单完整性失败，手续费未核对。");
                result.setMessage(reason);
            } else {
                // QUERY_FALLBACK：渠道侧视图逐笔回查（模拟银行口径，如实标注）
                result.setSource("QUERY_FALLBACK");
                matched = compareByQuery(code, day, dayText, internal, diffs, disputeIds, result);
                result.setFeeNote("降级口径无外部账单，手续费未做内外比对（CH-4 就绪后自动接入）。");
                result.setMessage("CH-4 账单不可用（" + billError + "），降级 queryChannelSide 渠道侧视图逐笔回查；"
                        + "无法检测外部多单（长款），仅核对内部流水的渠道侧金额/状态。");
            }

            result.setMatchedCount(matched);
            result.setLongCount(diffs.stream().filter(d -> "LONG".equals(d.getDiffType())).count());
            result.setShortCount(diffs.stream().filter(d -> "SHORT".equals(d.getDiffType())).count());
            result.setAmountCount(diffs.stream().filter(d -> "AMOUNT".equals(d.getDiffType())).count());
            result.setStatusCount(diffs.stream().filter(d -> "STATUS".equals(d.getDiffType())).count());
            result.setFeeCount(diffs.stream().filter(d -> "FEE".equals(d.getDiffType())).count());
            result.setNetDiff(result.getExternalTotal() - result.getInternalTotal());
            result.setPass(diffs.isEmpty());
            result.setDisputeCount(disputeIds.size());
            result.setDisputeIds(disputeIds);
            result.setDiffs(diffs.stream().map(ChannelReconService::toDTO).toList());
            if (result.getMessage() == null) {
                result.setMessage("逐笔比对: 一致 " + matched + " 笔，差异 " + diffs.size() + " 笔（长款 "
                        + result.getLongCount() + " 短款 " + result.getShortCount() + " 金额 " + result.getAmountCount()
                        + " 状态 " + result.getStatusCount() + " 手续费 " + result.getFeeCount()
                        + "），差错单 " + disputeIds.size() + " 个，净额差 " + result.getNetDiff() + " 分");
            }

            ChannelReconRunDO run = saveRun(result);
            result.setId(run.getId());
            result.setCreatedAt(run.getCreatedAt());
            result.setUpdatedAt(run.getUpdatedAt());
            boolean pass = Boolean.TRUE.equals(result.getPass());
            dailyReconService.upsertProgress(day, TYPE_CHANNEL, 0, 1, internal.size(), internal.size(),
                    pass ? 1 : 0, pass ? 0 : 1, disputeIds.size(), pass ? "SUCCESS" : "FAILED");
            batchMapperFinish(batch, run.getId(), internal.size(), pass, disputeIds.size(), result.getMessage());
            MetricsSupport.count("channel_recon_run", "channel", code, "result", pass ? "pass" : "diff");
            log.info("RC-3 渠道内外对账完成 channel={} day={} source={} pass={} diffs={} disputes={}",
                    code, dayText, result.getSource(), pass, diffs.size(), disputeIds.size());
            return result;
        } catch (Exception e) {
            batchMapperFinish(batch, null, 0, false, 0, "执行异常: " + e.getMessage());
            dailyReconService.upsertProgress(day, TYPE_CHANNEL, 0, 1, 0, 0, 0, 1, 0, "FAILED");
            log.error("RC-3 渠道内外对账失败 channel={} day={}（可重跑幂等）", code, dayText, e);
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ BILL 口径逐笔比对

    private long compareBill(String code, long day, String dayText, List<ChannelTradeDTO> internal,
                             List<ChannelBillDTO.Detail> details, ChannelBillDTO bill,
                             List<ChannelReconDiffDO> diffs, List<Long> disputeIds, ChannelReconResultDTO result) {
        Map<String, ChannelTradeDTO> byBizNo = new HashMap<>();
        for (ChannelTradeDTO t : internal) {
            byBizNo.put(t.getBizNo(), t);
        }
        Set<String> billed = new HashSet<>();
        long matched = 0;
        for (ChannelBillDTO.Detail d : details) {
            String bizNo = d.getBizNo() == null || d.getBizNo().isBlank() ? d.getChannelOrderNo() : d.getBizNo();
            billed.add(d.getBizNo());
            ChannelTradeDTO local = byBizNo.get(d.getBizNo());
            if (local == null) {
                // 长款：外部账单有、内部流水无（渠道多扣）
                String suggestion = "长款（渠道多扣）：建议差额挂账 SYS_SUSPENSE，人工核实后退汇/补单；不得自动冲正";
                DisputeDTO dispute = createDispute(code, dayText, DisputeClassifier.ONE_SIDED, bizNo, null,
                        nz(d.getAmount()), DisputeClassifier.SUSPENSE,
                        "外部账单有单、内部渠道流水缺失（长款）: channelOrderNo=" + d.getChannelOrderNo()
                                + " amount=" + d.getAmount() + " status=" + d.getStatus(), suggestion);
                disputeIds.add(dispute.getId());
                diffs.add(persistDiff(code, day, bizNo, d.getChannelOrderNo(), "LONG", null, d.getAmount(),
                        null, null, null, d.getStatus(), dispute, suggestion));
                MetricsSupport.count("channel_recon_diff", "channel", code, "type", "LONG");
            } else if (!Objects.equals(nz(local.getAmount()), nz(d.getAmount()))) {
                String suggestion = "金额不符：人工核对账单与平台流水，确认方向后红冲蓝补（账单差异不自动动账）";
                DisputeDTO dispute = createDispute(code, dayText, DisputeClassifier.AMOUNT_MISMATCH, bizNo,
                        local.getBizNo(), nz(d.getAmount()), DisputeClassifier.MANUAL,
                        "渠道账单金额与内部流水不符: 账单 " + d.getAmount() + " 分 vs 内部 " + local.getAmount() + " 分",
                        suggestion);
                disputeIds.add(dispute.getId());
                diffs.add(persistDiff(code, day, bizNo, d.getChannelOrderNo(), "AMOUNT", local.getAmount(),
                        d.getAmount(), null, null, local.getStatus(), d.getStatus(), dispute, suggestion));
                MetricsSupport.count("channel_recon_diff", "channel", code, "type", "AMOUNT");
            } else if (!Objects.equals(local.getStatus(), d.getStatus()) || !Objects.equals(local.getType(), d.getType())) {
                String suggestion = "状态不符：人工核实渠道终态（回执丢失/乱序可能），经补单收敛或人工修正，不自动动账";
                DisputeDTO dispute = createDispute(code, dayText, DisputeClassifier.STATUS_MISMATCH, bizNo,
                        local.getBizNo(), nz(d.getAmount()), DisputeClassifier.MANUAL,
                        "渠道账单状态/类型与内部流水不符: 账单 " + d.getStatus() + "/" + d.getType()
                                + " vs 内部 " + local.getStatus() + "/" + local.getType(), suggestion);
                disputeIds.add(dispute.getId());
                diffs.add(persistDiff(code, day, bizNo, d.getChannelOrderNo(), "STATUS", local.getAmount(),
                        d.getAmount(), null, null, local.getStatus(), d.getStatus(), dispute, suggestion));
                MetricsSupport.count("channel_recon_diff", "channel", code, "type", "STATUS");
            } else {
                matched++;
            }
        }
        // 短款：内部有、账单无（只判定时点快照之前的流水，账单下载后新增流水不属于本账单期）
        long cutoff = bill.getParsedAt() != null ? bill.getParsedAt()
                : (bill.getCreatedAt() == null ? Long.MAX_VALUE : bill.getCreatedAt());
        for (ChannelTradeDTO local : internal) {
            if (billed.contains(local.getBizNo())) {
                continue;
            }
            if (local.getCreatedAt() != null && local.getCreatedAt() > cutoff) {
                continue;
            }
            String suggestion = "短款（渠道未扣/漏账）：建议挂账 SYS_SUSPENSE 待人工补扣/核实渠道终态；保守不臆断冲正";
            DisputeDTO dispute = createDispute(code, dayText, DisputeClassifier.CHANNEL_MISSING, local.getBizNo(),
                    local.getBizNo(), nz(local.getAmount()), DisputeClassifier.SUSPENSE,
                    "内部渠道流水有单、外部账单缺失（短款/漏账）: status=" + local.getStatus()
                            + " amount=" + local.getAmount(), suggestion);
            disputeIds.add(dispute.getId());
            diffs.add(persistDiff(code, day, local.getBizNo(), local.getChannelOrderNo(), "SHORT",
                    local.getAmount(), null, null, null, local.getStatus(), null, dispute, suggestion));
            MetricsSupport.count("channel_recon_diff", "channel", code, "type", "SHORT");
        }
        return matched;
    }

    /**
     * 手续费内部口径一致性（只读）：payment.fee vs 结算手续费。
     * CH-4 账单无手续费列，外部手续费不可得 → 如实披露，不伪造内外比对。
     */
    private long checkInternalFee(String code, long day, String dayText, List<ChannelTradeDTO> internal,
                                  List<ChannelReconDiffDO> diffs, List<Long> disputeIds) {
        Map<String, Long> settleFee = new HashMap<>();
        try {
            for (SettleRecordDTO r : settleFacade.records(null).orThrow()) {
                if ("PAY".equals(r.getType())) {
                    settleFee.put(r.getBizId(), nz(r.getFee()));
                }
            }
        } catch (Exception e) {
            log.warn("RC-3 手续费核对：结算域不可达，跳过手续费一致性核对: {}", e.getMessage());
            return 0;
        }
        long checked = 0;
        for (ChannelTradeDTO t : internal) {
            if (!"PAY".equals(t.getType()) || !settleFee.containsKey(t.getBizNo())) {
                continue;
            }
            PaymentDTO payment;
            try {
                payment = paymentFacade.getPayment(t.getBizNo()).orThrow();
            } catch (Exception e) {
                continue; // 支付单缺失由六项对账覆盖，此处不重复建单
            }
            checked++;
            long payFee = nz(payment.getFee());
            long stlFee = settleFee.get(t.getBizNo());
            if (payFee != stlFee) {
                String suggestion = "手续费不符：差额挂账 SYS_SUSPENSE，人工核实费率快照后蓝补（手续费口径不变）";
                DisputeDTO dispute = createDispute(code, dayText, DisputeClassifier.FEE_MISMATCH, t.getBizNo(),
                        t.getBizNo(), Math.abs(payFee - stlFee), DisputeClassifier.SUSPENSE,
                        "手续费口径不一致: 支付单 fee=" + payFee + " vs 结算 fee=" + stlFee, suggestion);
                disputeIds.add(dispute.getId());
                diffs.add(persistDiff(code, day, t.getBizNo(), t.getChannelOrderNo(), "FEE",
                        t.getAmount(), t.getAmount(), payFee, stlFee, t.getStatus(), t.getStatus(), dispute, suggestion));
                MetricsSupport.count("channel_recon_diff", "channel", code, "type", "FEE");
            }
        }
        return checked;
    }

    // ------------------------------------------------------------------ QUERY_FALLBACK 口径

    private long compareByQuery(String code, long day, String dayText, List<ChannelTradeDTO> internal,
                                List<ChannelReconDiffDO> diffs, List<Long> disputeIds, ChannelReconResultDTO result) {
        long matched = 0;
        long externalTotal = 0;
        long externalCount = 0;
        for (ChannelTradeDTO local : internal) {
            ChannelTradeDTO side;
            try {
                side = channelFacade.queryChannelSide(local.getBizNo()).orThrow();
            } catch (Exception e) {
                try {
                    side = channelFacade.queryChannelTrade(local.getBizNo()).orThrow();
                } catch (Exception e2) {
                    String suggestion = "渠道侧查询不可达：人工核实渠道终态，不臆断失败";
                    DisputeDTO dispute = createDispute(code, dayText, DisputeClassifier.SOURCE_UNAVAILABLE,
                            local.getBizNo(), local.getBizNo(), nz(local.getAmount()), DisputeClassifier.MANUAL,
                            "渠道侧查询不可达（降级口径）: " + e2.getMessage(), suggestion);
                    disputeIds.add(dispute.getId());
                    diffs.add(persistDiff(code, day, local.getBizNo(), local.getChannelOrderNo(), "STATUS",
                            local.getAmount(), null, null, null, local.getStatus(), null, dispute, suggestion));
                    continue;
                }
            }
            externalCount++;
            externalTotal += Math.abs(nz(side.getAmount()));
            if (!Objects.equals(nz(local.getAmount()), nz(side.getAmount()))) {
                String suggestion = "金额不符：人工核对渠道侧与平台流水，确认方向后红冲蓝补";
                DisputeDTO dispute = createDispute(code, dayText, DisputeClassifier.AMOUNT_MISMATCH,
                        local.getBizNo(), local.getBizNo(), nz(side.getAmount()), DisputeClassifier.MANUAL,
                        "渠道侧金额与内部流水不符（降级口径）: 渠道侧 " + side.getAmount() + " vs 内部 " + local.getAmount(),
                        suggestion);
                disputeIds.add(dispute.getId());
                diffs.add(persistDiff(code, day, local.getBizNo(), local.getChannelOrderNo(), "AMOUNT",
                        local.getAmount(), side.getAmount(), null, null, local.getStatus(), side.getStatus(),
                        dispute, suggestion));
            } else if (!Objects.equals(local.getStatus(), side.getStatus())) {
                String suggestion = "状态不符：人工核实渠道终态，经补单收敛或人工修正";
                DisputeDTO dispute = createDispute(code, dayText, DisputeClassifier.STATUS_MISMATCH,
                        local.getBizNo(), local.getBizNo(), nz(local.getAmount()), DisputeClassifier.MANUAL,
                        "渠道侧状态与内部流水不符（降级口径）: 渠道侧 " + side.getStatus() + " vs 内部 " + local.getStatus(),
                        suggestion);
                disputeIds.add(dispute.getId());
                diffs.add(persistDiff(code, day, local.getBizNo(), local.getChannelOrderNo(), "STATUS",
                        local.getAmount(), side.getAmount(), null, null, local.getStatus(), side.getStatus(),
                        dispute, suggestion));
            } else {
                matched++;
            }
        }
        result.setExternalCount(externalCount);
        result.setExternalTotal(externalTotal);
        return matched;
    }

    // ------------------------------------------------------------------ 持久化 / 差错 / 工具

    private DisputeDTO createDispute(String code, String dayText, String type, String bizNo, String paymentId,
                                     long amount, String strategy, String reason, String suggestion) {
        DisputeCreateRequest request = new DisputeCreateRequest();
        request.setType(type);
        request.setSource("RECON");
        request.setCheckName(CHECK_NAME);
        request.setBizDate(dayText);
        request.setBizNo(bizNo);
        request.setPaymentId(paymentId);
        request.setAmount(amount);
        request.setStrategy(strategy);
        request.setReason(reason + "｜处置建议: " + suggestion);
        return disputeService.create(request);
    }

    private ChannelReconDiffDO persistDiff(String code, long day, String bizNo, String channelOrderNo, String diffType,
                                           Long internalAmount, Long externalAmount, Long internalFee, Long externalFee,
                                           String internalStatus, String externalStatus, DisputeDTO dispute,
                                           String suggestion) {
        ChannelReconDiffDO row = new ChannelReconDiffDO();
        row.setChannelCode(code);
        row.setBizDate(day);
        row.setBizNo(bizNo);
        row.setChannelOrderNo(channelOrderNo);
        row.setDiffType(diffType);
        row.setInternalAmount(internalAmount);
        row.setExternalAmount(externalAmount);
        row.setInternalFee(internalFee);
        row.setExternalFee(externalFee);
        row.setInternalStatus(internalStatus);
        row.setExternalStatus(externalStatus);
        row.setStatus(dispute == null ? "OPEN" : dispute.getStatus());
        row.setDisputeId(dispute == null ? null : dispute.getId());
        row.setSuggestion(suggestion);
        long now = System.currentTimeMillis();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        ChannelReconDiffDO existing = diffMapper.selectUnique(code, day, bizNo, diffType);
        if (existing == null) {
            row.setId(diffMapper.nextId());
            try {
                diffMapper.insert(row);
                return row;
            } catch (DuplicateKeyException dup) {
                existing = diffMapper.selectUnique(code, day, bizNo, diffType);
            }
        }
        row.setId(existing.getId());
        row.setCreatedAt(existing.getCreatedAt());
        diffMapper.refresh(row);
        return row;
    }

    private ChannelReconRunDO saveRun(ChannelReconResultDTO result) {
        long now = System.currentTimeMillis();
        ChannelReconRunDO existing = runMapper.selectUnique(result.getChannelCode(), result.getBizDate());
        ChannelReconRunDO row = existing == null ? new ChannelReconRunDO() : existing;
        if (existing == null) {
            row.setId("CR" + runMapper.nextId());
            row.setCreatedAt(now);
        }
        row.setChannelCode(result.getChannelCode());
        row.setBizDate(result.getBizDate());
        row.setSource(result.getSource());
        row.setBillId(result.getBillId());
        row.setInternalCount(result.getInternalCount());
        row.setExternalCount(result.getExternalCount());
        row.setMatchedCount(result.getMatchedCount());
        row.setLongCount(result.getLongCount());
        row.setShortCount(result.getShortCount());
        row.setAmountCount(result.getAmountCount());
        row.setStatusCount(result.getStatusCount());
        row.setFeeCount(result.getFeeCount());
        row.setInternalTotal(result.getInternalTotal());
        row.setExternalTotal(result.getExternalTotal());
        row.setNetDiff(result.getNetDiff());
        row.setPass(result.getPass());
        row.setDisputeCount(result.getDisputeCount());
        row.setFeeNote(truncate(result.getFeeNote()));
        row.setMessage(truncate(result.getMessage()));
        row.setUpdatedAt(now);
        if (existing == null) {
            try {
                runMapper.insert(row);
                return row;
            } catch (DuplicateKeyException dup) {
                ChannelReconRunDO winner = runMapper.selectUnique(result.getChannelCode(), result.getBizDate());
                row.setId(winner.getId());
                row.setCreatedAt(winner.getCreatedAt());
            }
        }
        runMapper.refresh(row);
        return row;
    }

    private void batchMapperFinish(ReconBatchDO batch, String reportId, int total, boolean pass,
                                   int disputes, String message) {
        batchMapper.finish(batch.getId(), pass ? "SUCCESS" : "FAILED", reportId, total, total,
                pass ? 1 : 0, pass ? 0 : 1, disputes, truncate(message), System.currentTimeMillis());
    }

    static ChannelReconDiffDTO toDTO(ChannelReconDiffDO row) {
        ChannelReconDiffDTO dto = new ChannelReconDiffDTO();
        dto.setId(row.getId());
        dto.setChannelCode(row.getChannelCode());
        dto.setBizDate(row.getBizDate());
        dto.setBizNo(row.getBizNo());
        dto.setChannelOrderNo(row.getChannelOrderNo());
        dto.setDiffType(row.getDiffType());
        dto.setInternalAmount(row.getInternalAmount());
        dto.setExternalAmount(row.getExternalAmount());
        dto.setInternalFee(row.getInternalFee());
        dto.setExternalFee(row.getExternalFee());
        dto.setInternalStatus(row.getInternalStatus());
        dto.setExternalStatus(row.getExternalStatus());
        dto.setStatus(row.getStatus());
        dto.setDisputeId(row.getDisputeId());
        dto.setSuggestion(row.getSuggestion());
        dto.setCreatedAt(row.getCreatedAt());
        dto.setUpdatedAt(row.getUpdatedAt());
        return dto;
    }

    /** 渠道归属：历史无渠道码行归属模拟银行主渠道（与 CH-4 MockBankAdapter.belongsHere 同口径）。 */
    private static boolean belongsHere(ChannelTradeDTO t, String code) {
        String tc = t.getChannelCode();
        if (tc == null || tc.isBlank()) {
            return DEFAULT_CHANNEL.equals(code);
        }
        return tc.equals(code);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static String truncate(String v) {
        return v == null ? null : (v.length() <= 512 ? v : v.substring(0, 512));
    }
}