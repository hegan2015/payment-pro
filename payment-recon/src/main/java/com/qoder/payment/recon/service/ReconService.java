package com.qoder.payment.recon.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.payment.common.db.IdGenerator;
import com.qoder.payment.common.db.ShardMirror;
import com.qoder.payment.common.db.ShardRuntime;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.accounting.TransferDTO;
import com.qoder.payment.facade.accounting.TrialDTO;
import com.qoder.payment.facade.channel.ChannelFacade;
import com.qoder.payment.facade.channel.ChannelTradeDTO;
import com.qoder.payment.facade.dispute.DisputeCreateRequest;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.facade.payment.PaymentFacade;
import com.qoder.payment.facade.recon.ReconCheckDTO;
import com.qoder.payment.facade.recon.ReconReportDTO;
import com.qoder.payment.facade.settle.SettleFacade;
import com.qoder.payment.facade.settle.SettleRecordDTO;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.TradeFacade;
import com.qoder.payment.recon.dataobject.ReconReportDO;
import com.qoder.payment.recon.mapper.ReconReportMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

/**
 * 对账中心：五方核对。PROCESSING 支付单（TCC 在途）不参与核对，
 * 任一域不可达时该项判失败并记录原因，不影响其余项。
 *
 * <p>RC-1：六项核对的缺失/不一致明细按差异性质分类（金额不一致/单边账/状态不一致/渠道缺失/域不可达），
 * 幂等生成差错单交 X-1（重复跑对账不重复建单）；核对通过时该项下未关闭差错单自动 RESOLVED（留痕）。
 * 差错接入失败不影响对账判定与返回结构（仅 additive 增 disputeType/missingBizNos/disputeIds 字段）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ReconReportMapper reconReportMapper;
    private final DisputeService disputeService;

    // I-5a：仅在 payment.sharding.enabled=true 时存在（local/未开启域 getIfAvailable()==null，零影响）
    private final ObjectProvider<ShardRuntime> shardRuntime;
    private final ObjectProvider<IdGenerator> idGenerator;
    private final ObjectProvider<ShardMirror> shardMirror;

    @DubboReference
    private AccountingFacade accountingFacade;

    @DubboReference
    private PaymentFacade paymentFacade;

    @DubboReference
    private TradeFacade tradeFacade;

    @DubboReference
    private SettleFacade settleFacade;

    @DubboReference
    private ChannelFacade channelFacade;

    public ReconReportDTO run() {
        return runSharded(0, 1);
    }

    /**
     * I-3 分片对账入口（RC-5 铺垫）：按支付单号 CRC32 取模把 SUCCESS 支付单分配到
     * {@code shardTotal} 个分片，各片只核对自己那份（不重不漏）；{@code run()} = {@code runSharded(0,1)}
     * 即全量，语义与改造前逐字节一致（控制台 /api/recon/run 与既有验证脚本不受影响）。
     *
     * <p>全局聚合项「账务试算平衡」只由分片 0 执行，其余分片标记为分片跳过（避免重复建单/重复告警）；
     * 「支付单采集」仍采集全量以便各片报告可见总量，逐笔核对项（账务/交易/清结算/渠道）只跑本片。
     *
     * <p>分片锁由 {@code ReconRunJob} 按 {@code reconRun:<idx>/<total>} 逐片加锁（各片可并行）。
     */
    public ReconReportDTO runSharded(int shardIndex, int shardTotal) {
        return doRun(shardIndex, shardTotal, null, "REALTIME");
    }

    /**
     * RC-2 日切批量对账入口：六项核对口径与实时完全一致（同一套 check 逻辑），
     * 只是数据范围按会计日过滤（payment.created_at 归日，Asia/Shanghai）。
     * PROCESSING 在途单不参与（与实时同口径）；FAILED 单语义校验同样按日执行。
     * 分片语义与 {@link #runSharded} 一致：CRC32 取模不重不漏，试算平衡只由分片 0 核对。
     */
    public ReconReportDTO runDailySharded(Long bizDate, int shardIndex, int shardTotal) {
        return doRun(shardIndex, shardTotal, bizDate, "DAILY");
    }

    private ReconReportDTO doRun(int shardIndex, int shardTotal, Long bizDate, String reconType) {
        int total = Math.max(1, shardTotal);
        int index = Math.max(0, Math.min(shardIndex, total - 1));
        log.info(total == 1 ? "对账开始" : "对账开始 shard=" + index + "/" + total);
        List<ReconCheckDTO> checks = new ArrayList<>();
        List<PaymentDTO> successPayments = new ArrayList<>();
        List<PaymentDTO> failedPayments = new ArrayList<>();
        AtomicInteger reconMissing = new AtomicInteger();
        Map<String, List<DisputeCreateRequest>> itemsByCheck = new LinkedHashMap<>();
        // 资金语义违规（FAILED 却留账务凭证 / 订单 PAID 指向 FAILED 单）：不翻聚合 pass，
        // 但必须建 MANUAL 差错单留痕待人工判定——独立于核对项成败，始终幂等建单。
        Map<String, List<DisputeCreateRequest>> semanticByCheck = new LinkedHashMap<>();

        List<DisputeCreateRequest> trialItems = itemsByCheck.computeIfAbsent("账务试算平衡", k -> new ArrayList<>());
        checks.add(check("账务试算平衡", () -> {
            if (total > 1 && index != 0) {
                // 全局聚合项不分片：只由分片 0 核对，其余分片跳过（不重复建单、不重复告警）
                return "分片 " + index + "/" + total + " 跳过（试算平衡由分片 0 核对）";
            }
            TrialDTO trial = accountingFacade.trial().orThrow();
            if (!Boolean.TRUE.equals(trial.getBalanced())) {
                String detail = "借贷不平衡 D=" + trial.getTotalDebit() + " C=" + trial.getTotalCredit();
                trialItems.add(item(DisputeClassifier.AMOUNT_MISMATCH, "TRIAL", null, 0L,
                        DisputeClassifier.MANUAL, detail));
                return detail;
            }
            if (trial.getBalanceSum() != 0) {
                String detail = "余额总和不为零: " + trial.getBalanceSum();
                trialItems.add(item(DisputeClassifier.AMOUNT_MISMATCH, "TRIAL", null, 0L,
                        DisputeClassifier.MANUAL, detail));
                return detail;
            }
            return "D=C=" + trial.getTotalDebit() + "，账户 " + trial.getAccounts() + " 个，分录 " + trial.getEntries() + " 条";
        }));

        List<DisputeCreateRequest> collectItems = itemsByCheck.computeIfAbsent("支付单采集", k -> new ArrayList<>());
        checks.add(check("支付单采集", () -> {
            try {
                List<PaymentDTO> all = paymentFacade.listPayments().orThrow();
                // RC-2 日切批量：按会计日过滤（payment.created_at 归日）；实时（bizDate=null）全量，口径不变。
                if (bizDate != null) {
                    all = all.stream().filter(p -> dayOf(p.getCreatedAt()) == bizDate).toList();
                }
                successPayments.addAll(all.stream()
                        .filter(p -> "SUCCESS".equals(p.getStatus()))
                        .toList());
                // 失败单同样要核：FAILED 却留有账务凭证 = 已动账未冲正（资金语义违规），
                // 只遍历 SUCCESS 会让这类违规与「订单 PAID 指向 FAILED 支付单」全部落在盲区。
                failedPayments.addAll(all.stream()
                        .filter(p -> "FAILED".equals(p.getStatus()))
                        .toList());
            } catch (Exception e) {
                collectItems.add(item(DisputeClassifier.SOURCE_UNAVAILABLE, "payment", null, 0L,
                        DisputeClassifier.MANUAL, "支付域不可达: " + e.getMessage()));
                throw e;
            }
            return bizDate != null
                    ? "会计日 " + dayText(bizDate) + " 成功支付单 " + successPayments.size() + " 笔"
                    : "成功支付单 " + successPayments.size() + " 笔";
        }));

        // I-3 分片切片：单片(0/1)即全量（与改造前同一份数据、同一判定），多片按 CRC32 取模不重不漏。
        final List<PaymentDTO> shardPayments = total == 1 ? successPayments
                : successPayments.stream().filter(p -> shardOf(p.getId(), total) == index).toList();
        final List<PaymentDTO> shardFailed = total == 1 ? failedPayments
                : failedPayments.stream().filter(p -> shardOf(p.getId(), total) == index).toList();
        if (total > 1) {
            log.info("对账分片切片 shard={}/{} 全量={} 本片={}", index, total, successPayments.size(), shardPayments.size());
        }

        List<DisputeCreateRequest> acctItems = itemsByCheck.computeIfAbsent("支付-账务一致", k -> new ArrayList<>());
        checks.add(check("支付-账务一致", () -> {
            int missing = 0;
            int errors = 0;
            for (PaymentDTO payment : shardPayments) {
                try {
                    TransferDTO transfer = accountingFacade.transfer(payment.getId()).orThrow();
                    long debit = transfer.getLines().stream()
                            .filter(l -> "D".equals(l.getDir()))
                            .mapToLong(l -> l.getAmount()).sum();
                    if (debit != payment.getAmount()) {
                        missing++;
                        acctItems.add(item(DisputeClassifier.AMOUNT_MISMATCH, payment.getId(), payment.getId(),
                                payment.getAmount(), DisputeClassifier.REVERSE_AND_REPOST,
                                "账务借方 " + debit + " ≠ 支付金额 " + payment.getAmount()));
                    }
                } catch (Exception e) {
                    // RC-J COMBO 组合支付（I 泳道 P-6）：账务凭证按腿落账（bizNo=legId），主单无直接凭证。
                    // 折进既有「支付-账务一致」项：逐腿凭证借方归集 == 主单金额即一致（只读回查既有 facade，
                    // 非 COMBO 判定口径逐字节不变；腿凭证缺失仍判单边账）。
                    if ("COMBO".equals(payment.getMethod())) {
                        try {
                            PaymentDTO full = paymentFacade.getPayment(payment.getId()).orThrow();
                            List<com.qoder.payment.facade.payment.PaymentLegDTO> legs = full.getLegs();
                            if (legs != null && !legs.isEmpty()) {
                                long legDebit = 0;
                                for (com.qoder.payment.facade.payment.PaymentLegDTO leg : legs) {
                                    TransferDTO lt = accountingFacade.transfer(leg.getId()).orThrow();
                                    legDebit += lt.getLines().stream()
                                            .filter(l -> "D".equals(l.getDir()))
                                            .mapToLong(l -> l.getAmount()).sum();
                                }
                                if (legDebit != payment.getAmount()) {
                                    missing++;
                                    acctItems.add(item(DisputeClassifier.AMOUNT_MISMATCH, payment.getId(), payment.getId(),
                                            payment.getAmount(), DisputeClassifier.REVERSE_AND_REPOST,
                                            "COMBO 各腿凭证借方合计 " + legDebit + " ≠ 支付金额 " + payment.getAmount()));
                                }
                                continue;
                            }
                        } catch (Exception legEx) {
                            // 腿凭证缺失/不可达 → 落入下方单边账判定（不掩盖真实缺失）
                        }
                    }
                    missing++;
                    errors++;
                    acctItems.add(item(DisputeClassifier.ONE_SIDED, payment.getId(), payment.getId(),
                            payment.getAmount(), DisputeClassifier.SUSPENSE,
                            "账务凭证缺失/不可达（单边账）: " + e.getMessage()));
                }
            }
            int failedDirty = 0;
            for (PaymentDTO payment : shardFailed) {
                try {
                    TransferDTO transfer = accountingFacade.transfer(payment.getId()).orThrow();
                    if (transfer != null && transfer.getLines() != null && !transfer.getLines().isEmpty()) {
                        failedDirty++;
                        DisputeCreateRequest violation = item(DisputeClassifier.AMOUNT_MISMATCH, payment.getId(), payment.getId(),
                                payment.getAmount(), DisputeClassifier.MANUAL,
                                "失败单已动账未冲正：FAILED 却留有账务凭证 " + transfer.getLines().size()
                                        + " 腿（修正方向须人工判定：红冲退款 或 依全局 CONFIRMED 修正为 SUCCESS）");
                        acctItems.add(violation);
                        semanticByCheck.computeIfAbsent("支付-账务一致", k -> new ArrayList<>()).add(violation);
                    }
                } catch (Exception expected) {
                    // 失败单无凭证正是期望态，不计缺失、不建单
                }
            }
            // 语义违规照样建单+告警，但不翻聚合 pass：历史脏单的修正方向（红冲退款 vs 修正为 SUCCESS）
            // 属资金判定，须人工/用户拍板，不能由对账自动动账。违规经差错平台工单闭环跟踪。
            if (failedDirty > 0) {
                log.error("资金语义违规：{} 笔 FAILED 支付单已动账未冲正（已建差错单待人工判定）", failedDirty);
                MetricsSupport.count("recon_semantic_violation", "kind", "failed_with_entries");
            }
            if (!shardPayments.isEmpty() && errors == shardPayments.size()) {
                // 全量异常=账务域不可达：SOURCE_UNAVAILABLE 而非逐笔资金差错（故障隔离，不误报）
                acctItems.clear();
                acctItems.add(item(DisputeClassifier.SOURCE_UNAVAILABLE, "accounting", null, 0L,
                        DisputeClassifier.MANUAL, "账务域不可达，非资金差错"));
            }
            reconMissing.set(missing);
            String acctDetail = missing == 0
                    ? shardPayments.size() + " 笔支付均有等额账务转账"
                    : missing + " 笔支付缺失账务转账";
            return failedDirty > 0
                    ? acctDetail + "；另有 " + failedDirty + " 笔 FAILED 单已动账未冲正（已建单待人工判定）"
                    : acctDetail;
        }, () -> reconMissing.get() == 0 ? null : "存在缺失"));

        List<DisputeCreateRequest> tradeItems = itemsByCheck.computeIfAbsent("支付-交易一致", k -> new ArrayList<>());
        checks.add(check("支付-交易一致", () -> {
            int bad = 0;
            int errors = 0;
            for (PaymentDTO payment : shardPayments) {
                try {
                    OrderDTO order = tradeFacade.getOrder(payment.getOrderId()).orThrow();
                    if (!"PAID".equals(order.getStatus()) && !"PARTIAL_REFUNDED".equals(order.getStatus())
                            && !"REFUNDED".equals(order.getStatus())) {
                        bad++;
                        tradeItems.add(item(DisputeClassifier.STATUS_MISMATCH, payment.getId(), payment.getId(),
                                payment.getAmount(), DisputeClassifier.MANUAL,
                                "支付 SUCCESS 但订单状态 " + order.getStatus()));
                    }
                } catch (Exception e) {
                    bad++;
                    errors++;
                    tradeItems.add(item(DisputeClassifier.STATUS_MISMATCH, payment.getId(), payment.getId(),
                            payment.getAmount(), DisputeClassifier.MANUAL,
                            "订单缺失/不可达: " + e.getMessage()));
                }
            }
            int failedOrderDirty = 0;
            for (PaymentDTO payment : shardFailed) {
                if (payment.getOrderId() == null || payment.getOrderId().isBlank()) {
                    continue;
                }
                try {
                    OrderDTO order = tradeFacade.getOrder(payment.getOrderId()).orThrow();
                    boolean orderPaid = "PAID".equals(order.getStatus())
                            || "PARTIAL_REFUNDED".equals(order.getStatus())
                            || "REFUNDED".equals(order.getStatus());
                    if (orderPaid && payment.getId().equals(order.getPaymentId())) {
                        failedOrderDirty++;
                        DisputeCreateRequest violation = item(DisputeClassifier.STATUS_MISMATCH, payment.getId(), payment.getId(),
                                payment.getAmount(), DisputeClassifier.MANUAL,
                                "订单 " + order.getStatus() + " 指向 FAILED 支付单（终态与订单不一致，需人工判定修正方向）");
                        tradeItems.add(violation);
                        semanticByCheck.computeIfAbsent("支付-交易一致", k -> new ArrayList<>()).add(violation);
                    }
                } catch (Exception expected) {
                    // 订单缺失/不可达由 SUCCESS 侧故障隔离判定，此处不重复建单
                }
            }
            if (failedOrderDirty > 0) {
                log.error("资金语义违规：{} 笔订单已支付却指向 FAILED 支付单（已建差错单待人工判定）", failedOrderDirty);
                MetricsSupport.count("recon_semantic_violation", "kind", "paid_order_failed_payment");
            }
            if (!shardPayments.isEmpty() && errors == shardPayments.size()) {
                tradeItems.clear();
                tradeItems.add(item(DisputeClassifier.SOURCE_UNAVAILABLE, "trade", null, 0L,
                        DisputeClassifier.MANUAL, "交易域不可达，非资金差错"));
            }
            reconMissing.set(bad);
            String tradeDetail = bad == 0 ? "订单状态全部一致" : bad + " 笔订单状态不一致";
            return failedOrderDirty > 0
                    ? tradeDetail + "；另有 " + failedOrderDirty + " 笔订单指向 FAILED 支付单（已建单待人工判定）"
                    : tradeDetail;
        }, () -> reconMissing.get() == 0 ? null : "存在不一致"));

        List<DisputeCreateRequest> settleItems = itemsByCheck.computeIfAbsent("支付-清结算一致", k -> new ArrayList<>());
        checks.add(check("支付-清结算一致", () -> {
            Set<String> settled;
            try {
                settled = settleFacade.records(null).orThrow().stream()
                        .filter(r -> "PAY".equals(r.getType()))
                        .map(SettleRecordDTO::getBizId)
                        .collect(Collectors.toSet());
            } catch (Exception e) {
                settleItems.add(item(DisputeClassifier.SOURCE_UNAVAILABLE, "settle", null, 0L,
                        DisputeClassifier.MANUAL, "清结算域不可达: " + e.getMessage()));
                throw e;
            }
            int missing = 0;
            for (PaymentDTO p : shardPayments) {
                if (!settled.contains(p.getId())) {
                    missing++;
                    settleItems.add(item(DisputeClassifier.ONE_SIDED, p.getId(), p.getId(), p.getAmount(),
                            DisputeClassifier.MANUAL, "结算单缺失（outbox 在途或投递失败）"));
                }
            }
            reconMissing.set(missing);
            return missing == 0 ? "结算单全部齐备" : missing + " 笔支付缺失结算单（outbox 在途或投递失败）";
        }, () -> reconMissing.get() == 0 ? null : "存在缺失"));

        List<DisputeCreateRequest> channelItems = itemsByCheck.computeIfAbsent("支付-渠道一致", k -> new ArrayList<>());
        checks.add(check("支付-渠道一致", () -> {
            Set<String> captured;
            try {
                captured = channelFacade.listTrades().orThrow().stream()
                        .filter(t -> "CAPTURED".equals(t.getStatus()) || "REFUNDED".equals(t.getStatus()))
                        .map(ChannelTradeDTO::getBizNo)
                        .collect(Collectors.toSet());
            } catch (Exception e) {
                channelItems.add(item(DisputeClassifier.SOURCE_UNAVAILABLE, "channel", null, 0L,
                        DisputeClassifier.MANUAL, "渠道域不可达: " + e.getMessage()));
                throw e;
            }
            int missing = 0;
            for (PaymentDTO p : shardPayments) {
                if ("CARD".equals(p.getMethod()) && !captured.contains(p.getId())) {
                    missing++;
                    channelItems.add(item(DisputeClassifier.CHANNEL_MISSING, p.getId(), p.getId(), p.getAmount(),
                            DisputeClassifier.SUSPENSE, "银行卡支付成功但渠道扣款缺失（短款挂账待人工）"));
                }
            }
            reconMissing.set(missing);
            return missing == 0 ? "银行卡渠道扣款全部齐备" : missing + " 笔银行卡支付缺失渠道扣款";
        }, () -> reconMissing.get() == 0 ? null : "存在缺失"));

        boolean pass = checks.stream().allMatch(c -> Boolean.TRUE.equals(c.getPass()));
        MetricsSupport.reconResult(pass);
        long now = System.currentTimeMillis();
        ReconReportDO record = new ReconReportDO();
        record.setId(newReportId());
        record.setCheckedAt(now);
        record.setPass(pass);
        record.setBizDate(bizDate);
        record.setReconType(reconType);

        // RC-1 差错接入（失败不影响对账判定）：失败项幂等建单；通过项自动核销未关闭单（留痕）
        List<Long> disputeIds = ingestDisputes(checks, itemsByCheck, semanticByCheck, record.getId(), total, bizDate);
        record.setReport(toJson(checks));
        reconReportMapper.insert(record);
        mirrorToSingleTable(record);

        ReconReportDTO dto = new ReconReportDTO();
        dto.setId(record.getId());
        dto.setCheckedAt(now);
        dto.setPass(pass);
        dto.setChecks(checks);
        dto.setDisputeIds(disputeIds);
        dto.setBizDate(bizDate);
        dto.setReconType(reconType);
        log.info("对账完成 reportId={} pass={} 通过项={}/{} 差错单={}", record.getId(), pass,
                checks.stream().filter(c -> Boolean.TRUE.equals(c.getPass())).count(), checks.size(), disputeIds.size());
        return dto;
    }

    private List<Long> ingestDisputes(List<ReconCheckDTO> checks,
                                      Map<String, List<DisputeCreateRequest>> itemsByCheck,
                                      Map<String, List<DisputeCreateRequest>> semanticByCheck,
                                      String reportId, int shardTotal, Long bizDate) {
        List<Long> disputeIds = new ArrayList<>();
        // RC-2：日切批量按会计日建单（bizDate 参与幂等维度）；实时仍用当天日期，口径不变。
        String bizDateText = bizDate != null ? dayText(bizDate)
                : LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        // 资金语义违规单：独立于核对项成败始终幂等建单（MANUAL，不自动动账，待人工/用户拍板修正方向）。
        // 核对项通过时也不遗漏——检出与处置解耦，违规经差错平台工单闭环跟踪。
        for (Map.Entry<String, List<DisputeCreateRequest>> entry : semanticByCheck.entrySet()) {
            for (DisputeCreateRequest item : entry.getValue()) {
                try {
                    item.setCheckName(entry.getKey());
                    item.setBizDate(bizDateText);
                    Long id = disputeService.create(item).getId();
                    if (!disputeIds.contains(id)) {
                        disputeIds.add(id);
                    }
                } catch (Exception e) {
                    log.error("语义违规差错接入失败（不影响对账判定） check={} bizNo={}",
                            entry.getKey(), item.getBizNo(), e);
                }
            }
        }
        for (ReconCheckDTO check : checks) {
            List<DisputeCreateRequest> items = itemsByCheck.getOrDefault(check.getName(), List.of());
            try {
                if (!items.isEmpty()) {
                    check.setDisputeType(items.get(0).getType());
                    check.setMissingBizNos(items.stream().map(DisputeCreateRequest::getBizNo).toList());
                }
                if (Boolean.TRUE.equals(check.getPass())) {
                    // I-3 分片模式下本片只核对了自己那份，不足以判定全局通过 → 不做自动核销，
                    // 避免误核销他片差错单（掩盖真实资金差错）；单片全量(0/1)语义与改造前一致。
                    // RC-2 日切批量同理：按日通过不代表全局通过，不做自动核销（只建单不核销）。
                    if (shardTotal == 1 && bizDate == null) {
                        disputeIds.addAll(disputeService.autoResolveByCheck(check.getName(),
                                "对账复核通过 reportId=" + reportId));
                    }
                } else {
                    for (DisputeCreateRequest item : items) {
                        item.setCheckName(check.getName());
                        item.setBizDate(bizDateText);
                        Long id = disputeService.create(item).getId();
                        if (!disputeIds.contains(id)) {
                            disputeIds.add(id);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("差错接入失败（不影响对账判定） check={}", check.getName(), e);
            }
        }
        return disputeIds;
    }

    private static DisputeCreateRequest item(String type, String bizNo, String paymentId, Long amount,
                                             String strategy, String reason) {
        DisputeCreateRequest request = new DisputeCreateRequest();
        request.setType(type);
        request.setSource("RECON");
        request.setBizNo(bizNo);
        request.setPaymentId(paymentId);
        request.setAmount(amount);
        request.setStrategy(strategy);
        request.setReason(reason);
        return request;
    }

    /**
     * I-3 分片归属算法：支付单号 CRC32 取模（跨 JVM 确定性、分布均匀）。
     * 调度中心分片广播的 shardIndex/shardTotal 与此一致，保证各片不重不漏。
     */
    public static int shardOf(String bizId, int shardTotal) {
        if (shardTotal <= 1) {
            return 0;
        }
        CRC32 crc = new CRC32();
        crc.update((bizId == null ? "" : bizId).getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % shardTotal);
    }

    /** 分片计划（只读、不执行核对）：供 /actuator/pprecon 与 verify-job.py 断言「不重不漏」。 */
    public Map<Integer, List<String>> shardPlan(int shardTotal) {
        int total = Math.max(1, shardTotal);
        List<String> ids = paymentFacade.listPayments().orThrow().stream()
                .filter(p -> "SUCCESS".equals(p.getStatus()))
                .map(PaymentDTO::getId)
                .toList();
        Map<Integer, List<String>> plan = new LinkedHashMap<>();
        for (int i = 0; i < total; i++) {
            plan.put(i, new ArrayList<>());
        }
        for (String id : ids) {
            plan.get(shardOf(id, total)).add(id);
        }
        return plan;
    }

    public ReconReportDTO latest() {
        // I-5a 读己之写：刚写入的报告必须立即可见（强制走主库）
        ReconReportDO record = readYourWrite(reconReportMapper::selectLatest);
        if (record == null) {
            return null;
        }
        return toDTO(record, true);
    }

    /** RC-4 单报告全文（含六项 checks 明细；分片键 id 等值单片路由）。 */
    public ReconReportDTO getReport(String reportId) {
        ReconReportDO record = readYourWrite(() -> reconReportMapper.selectById(reportId));
        return record == null ? null : toDTO(record, true);
    }

    /** RC-4 报告元数据跨片分页（不取 report 大字段；归并口径与 reportsPage 一致，不重不漏）。 */
    public List<ReconReportDTO> reportsMetaPage(Long bizDate, String reconType, int offset, int size) {
        int from = Math.max(0, offset);
        int limit = Math.max(1, Math.min(size, 500));
        String type = reconType == null || reconType.isBlank() ? null : reconType;
        List<ReconReportDO> rows = readYourWrite(() -> reconReportMapper.selectMetaPage(bizDate, type, from, limit));
        List<ReconReportDTO> out = new ArrayList<>();
        for (ReconReportDO row : rows) {
            out.add(toDTO(row, false));
        }
        return out;
    }

    public long reportsMetaCount(Long bizDate, String reconType) {
        String type = reconType == null || reconType.isBlank() ? null : reconType;
        return readYourWrite(() -> reconReportMapper.countMeta(bizDate, type));
    }

    /** RC-4 汇总用全量元数据（无大字段）。 */
    public List<ReconReportDTO> allReportsMeta() {
        return readYourWrite(reconReportMapper::selectAllMeta).stream().map(r -> toDTO(r, false)).toList();
    }

    private ReconReportDTO toDTO(ReconReportDO record, boolean withChecks) {
        ReconReportDTO dto = new ReconReportDTO();
        dto.setId(record.getId());
        dto.setCheckedAt(record.getCheckedAt());
        dto.setPass(record.getPass());
        dto.setBizDate(record.getBizDate());
        dto.setReconType(record.getReconType());
        dto.setChannelCode(record.getChannelCode());
        if (withChecks) {
            dto.setChecks(fromJson(record.getReport()));
        }
        return dto;
    }

    /** RC-2 归日：epoch millis → Asia/Shanghai 当日 00:00 epoch millis（与 accounting 会计日同口径）。 */
    public static long dayOf(Long epochMillis) {
        long millis = epochMillis == null ? 0L : epochMillis;
        return java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .toLocalDate().atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }

    /** 会计日 epoch millis → yyyyMMdd（差错单 bizDate 维度）。 */
    public static String dayText(long bizDate) {
        return java.time.Instant.ofEpochMilli(bizDate).atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    /**
     * I-5a 跨片分页（只读、additive，不改 facade 契约）：报告按 checked_at DESC,id DESC 归并，
     * 供 verify-sharding.py 断言「跨片分页不重不漏」。report 正文不取，避免大字段无谓传输。
     */
    public List<Map<String, Object>> reportsPage(int offset, int size) {
        int from = Math.max(0, offset);
        int limit = Math.max(1, Math.min(size, 500));
        List<ReconReportDO> rows = readYourWrite(() -> reconReportMapper.selectPage(from, limit));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReconReportDO row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("checkedAt", row.getCheckedAt());
            item.put("pass", row.getPass());
            out.add(item);
        }
        return out;
    }

    public long reportCount() {
        return readYourWrite(reconReportMapper::countAll);
    }

    /**
     * I-5a 取号：分片开启走全局发号（雪花，避免各片 sequences 重号 E-7）；
     * 未开启（local 默认）仍走 {@code next_value_for('seq_recon')}，ID 形态与改造前逐字节一致。
     */
    private String newReportId() {
        ShardRuntime runtime = shardRuntime.getIfAvailable();
        if (runtime != null && runtime.isActive()) {
            return idGenerator.getObject().next("RC");
        }
        return "RC" + reconReportMapper.nextId();
    }

    /** I-5a 影子写：分片期 additive 镜像回原单表，保障 enabled=false 一键回滚后结果一致。 */
    private void mirrorToSingleTable(ReconReportDO record) {
        ShardMirror mirror = shardMirror.getIfAvailable();
        if (mirror == null) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", record.getId());
        row.put("checked_at", record.getCheckedAt());
        row.put("pass", record.getPass());
        row.put("report", record.getReport());
        row.put("biz_date", record.getBizDate());
        row.put("recon_type", record.getReconType());
        row.put("channel_code", record.getChannelCode());
        mirror.insert("recon_report", row);
    }

    private <T> T readYourWrite(Supplier<T> action) {
        ShardRuntime runtime = shardRuntime.getIfAvailable();
        return runtime == null ? action.get() : runtime.readYourWrite(action);
    }

    private ReconCheckDTO check(String name, Supplier<String> detailAction) {
        return check(name, detailAction, () -> null);
    }

    /** detailPrefix 非空时视为失败（用于“存在缺失/不一致”类判定）。 */
    private ReconCheckDTO check(String name, Supplier<String> detailAction, Supplier<String> failSignal) {
        ReconCheckDTO check = new ReconCheckDTO();
        check.setName(name);
        try {
            String detail = detailAction.get();
            String signal = failSignal.get();
            check.setPass(signal == null);
            check.setDetail(detail);
        } catch (Exception e) {
            check.setPass(false);
            check.setDetail("核对异常: " + e.getMessage());
        }
        if (!Boolean.TRUE.equals(check.getPass())) MetricsSupport.reconDispute(name);
        return check;
    }

    private static String toJson(List<ReconCheckDTO> checks) {
        try {
            return MAPPER.writeValueAsString(checks);
        } catch (Exception e) {
            throw new IllegalStateException("对账报告序列化失败", e);
        }
    }

    private static List<ReconCheckDTO> fromJson(String json) {
        try {
            return MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, ReconCheckDTO.class));
        } catch (Exception e) {
            throw new IllegalStateException("对账报告反序列化失败", e);
        }
    }
}