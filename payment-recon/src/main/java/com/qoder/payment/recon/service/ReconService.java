package com.qoder.payment.recon.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.accounting.TransferDTO;
import com.qoder.payment.facade.accounting.TrialDTO;
import com.qoder.payment.facade.channel.ChannelFacade;
import com.qoder.payment.facade.channel.ChannelTradeDTO;
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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 对账中心：五方核对。PROCESSING 支付单（TCC 在途）不参与核对，
 * 任一域不可达时该项判失败并记录原因，不影响其余项。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ReconReportMapper reconReportMapper;

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
        log.info("对账开始");
        List<ReconCheckDTO> checks = new ArrayList<>();
        List<PaymentDTO> successPayments = new ArrayList<>();
        AtomicInteger reconMissing = new AtomicInteger();

        checks.add(check("账务试算平衡", () -> {
            TrialDTO trial = accountingFacade.trial().orThrow();
            if (!Boolean.TRUE.equals(trial.getBalanced())) {
                return "借贷不平衡 D=" + trial.getTotalDebit() + " C=" + trial.getTotalCredit();
            }
            if (trial.getBalanceSum() != 0) {
                return "余额总和不为零: " + trial.getBalanceSum();
            }
            return "D=C=" + trial.getTotalDebit() + "，账户 " + trial.getAccounts() + " 个，分录 " + trial.getEntries() + " 条";
        }));

        checks.add(check("支付单采集", () -> {
            successPayments.addAll(paymentFacade.listPayments().orThrow().stream()
                    .filter(p -> "SUCCESS".equals(p.getStatus()))
                    .toList());
            return "成功支付单 " + successPayments.size() + " 笔";
        }));

        checks.add(check("支付-账务一致", () -> {
            int missing = 0;
            for (PaymentDTO payment : successPayments) {
                try {
                    TransferDTO transfer = accountingFacade.transfer(payment.getId()).orThrow();
                    long debit = transfer.getLines().stream()
                            .filter(l -> "D".equals(l.getDir()))
                            .mapToLong(l -> l.getAmount()).sum();
                    if (debit != payment.getAmount()) {
                        missing++;
                    }
                } catch (Exception e) {
                    missing++;
                }
            }
            reconMissing.set(missing);
            return missing == 0 ? successPayments.size() + " 笔支付均有等额账务转账" : missing + " 笔支付缺失账务转账";
        }, () -> reconMissing.get() == 0 ? null : "存在缺失"));

        checks.add(check("支付-交易一致", () -> {
            int bad = 0;
            for (PaymentDTO payment : successPayments) {
                try {
                    OrderDTO order = tradeFacade.getOrder(payment.getOrderId()).orThrow();
                    if (!"PAID".equals(order.getStatus()) && !"PARTIAL_REFUNDED".equals(order.getStatus())
                            && !"REFUNDED".equals(order.getStatus())) {
                        bad++;
                    }
                } catch (Exception e) {
                    bad++;
                }
            }
            reconMissing.set(bad);
            return bad == 0 ? "订单状态全部一致" : bad + " 笔订单状态不一致";
        }, () -> reconMissing.get() == 0 ? null : "存在不一致"));

        checks.add(check("支付-清结算一致", () -> {
            Set<String> settled = settleFacade.records(null).orThrow().stream()
                    .filter(r -> "PAY".equals(r.getType()))
                    .map(SettleRecordDTO::getBizId)
                    .collect(Collectors.toSet());
            int missing = (int) successPayments.stream().filter(p -> !settled.contains(p.getId())).count();
            reconMissing.set(missing);
            return missing == 0 ? "结算单全部齐备" : missing + " 笔支付缺失结算单（outbox 在途或投递失败）";
        }, () -> reconMissing.get() == 0 ? null : "存在缺失"));

        checks.add(check("支付-渠道一致", () -> {
            Set<String> captured = channelFacade.listTrades().orThrow().stream()
                    .filter(t -> "CAPTURED".equals(t.getStatus()) || "REFUNDED".equals(t.getStatus()))
                    .map(ChannelTradeDTO::getBizNo)
                    .collect(Collectors.toSet());
            int missing = (int) successPayments.stream()
                    .filter(p -> "CARD".equals(p.getMethod()))
                    .filter(p -> !captured.contains(p.getId()))
                    .count();
            reconMissing.set(missing);
            return missing == 0 ? "银行卡渠道扣款全部齐备" : missing + " 笔银行卡支付缺失渠道扣款";
        }, () -> reconMissing.get() == 0 ? null : "存在缺失"));

        boolean pass = checks.stream().allMatch(c -> Boolean.TRUE.equals(c.getPass()));
        long now = System.currentTimeMillis();
        ReconReportDO record = new ReconReportDO();
        record.setId("RC" + reconReportMapper.nextId());
        record.setCheckedAt(now);
        record.setPass(pass);
        record.setReport(toJson(checks));
        reconReportMapper.insert(record);

        ReconReportDTO dto = new ReconReportDTO();
        dto.setId(record.getId());
        dto.setCheckedAt(now);
        dto.setPass(pass);
        dto.setChecks(checks);
        log.info("对账完成 reportId={} pass={} 通过项={}/{}", record.getId(), pass,
                checks.stream().filter(c -> Boolean.TRUE.equals(c.getPass())).count(), checks.size());
        return dto;
    }

    public ReconReportDTO latest() {
        ReconReportDO record = reconReportMapper.selectLatest();
        if (record == null) {
            return null;
        }
        ReconReportDTO dto = new ReconReportDTO();
        dto.setId(record.getId());
        dto.setCheckedAt(record.getCheckedAt());
        dto.setPass(record.getPass());
        dto.setChecks(fromJson(record.getReport()));
        return dto;
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