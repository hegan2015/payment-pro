package com.qoder.payment.accounting.service;

import com.qoder.payment.accounting.dataobject.ReserveCheckLogDO;
import com.qoder.payment.accounting.daycut.AccountingDayClock;
import com.qoder.payment.accounting.mapper.ReserveCheckMapper;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.facade.accounting.ReserveCheckDTO;
import com.qoder.payment.facade.accounting.ReserveReconDTO;
import com.qoder.payment.facade.channel.ChannelBillDTO;
import com.qoder.payment.facade.channel.ChannelFacade;
import com.qoder.payment.facade.dispute.DisputeCreateRequest;
import com.qoder.payment.facade.dispute.DisputeFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AC-5 账实核对（完善 S-7 雏形）：账务余额 vs 渠道存管/银行侧实际余额，按会计日 + 多渠道。
 *
 * <p><b>账面</b>：SYS_CASH 复式记账余额（权威，口径一字未改）。
 * <p><b>实际</b>：
 * <ol>
 *   <li>P0 模拟银行存管口径<b>完整保留</b>：book + Σ{@code reserve_bank_adjust}（银行侧独立事件，
 *       只影响实际侧，绝不触碰账务凭证与余额）；</li>
 *   <li>AC-5 新增真实渠道证据：CH-4 渠道账单（{@code ChannelFacade.listBills/getBillDetail}，<b>只调不改</b>）
 *       的逐笔金额差异 {@code Σ(billAmount − localAmount)} 归集到实际侧 —— 渠道说钱动了而账上没动，
 *       即账实差异。CH-4 未就绪/无账单时该项为 0，结果与 P0 逐值一致。</li>
 * </ol>
 *
 * <p><b>差异处置</b>：分级告警（NONE/WARN/ERROR/CRITICAL，阈值 DynamicConfig 可调）
 * + ERROR 日志 + {@code payment_accounting_reserve_diff_total} 指标 + reserve_check_log 留痕（带会计日/渠道）
 * + 差错单（{@code DisputeFacade}，source=RESERVE、type=AMOUNT_MISMATCH、strategy=MANUAL，
 * 幂等键 {@code (RESERVE_CHECK, bizDate:channel)}，重复核对不重复建单）。
 * 差错平台不可达只降级告警，不阻断核对；银行侧差异不臆断冲正（待人工/差错平台核销）。
 *
 * <p>与五方对账互补：五方是内部一致，账实是内外一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReserveReconService {

    private final ReserveCheckMapper reserveCheckMapper;
    private final AccountingService accountingService;
    private final DynamicConfig dynamicConfig;

    @DubboReference(check = false, timeout = 3000, retries = 0)
    private DisputeFacade disputeFacade;

    /** CH-4 渠道账单（P1-A）：只读调用，未就绪时降级为纯模拟银行口径。 */
    @DubboReference(check = false, timeout = 5000, retries = 0)
    private ChannelFacade channelFacade;

    /** 账实核对（按会计日 + 多渠道汇总）；channels 为空取配置默认（MOCK_BANK）。 */
    public ReserveReconDTO recon(Long bizDate, List<String> channels) {
        long day = bizDate == null ? AccountingDayClock.today() : AccountingDayClock.dayStart(bizDate);
        long book = accountingService.balance(AccountingService.SYS_CASH);
        long warn = dynamicConfig.getLong(ReserveDiff.KEY_WARN, ReserveDiff.DEFAULT_WARN);
        long critical = dynamicConfig.getLong(ReserveDiff.KEY_CRITICAL, ReserveDiff.DEFAULT_CRITICAL);
        List<String> channelList = resolveChannels(channels);
        boolean billReady = false;
        long totalAdjust = 0;
        List<ReserveReconDTO.ChannelDetail> details = new ArrayList<>();
        for (String channel : channelList) {
            long bankAdjust = reserveCheckMapper.sumAdjustByChannel(channel);
            BillDiff bill = ReserveDiff.MOCK_BANK.equals(channel) ? BillDiff.empty() : channelBillDiff(channel, day);
            billReady = billReady || bill.ready;
            long actual = book + bankAdjust + bill.amount;
            long diff = book - actual;
            totalAdjust += bankAdjust + bill.amount;
            String level = ReserveDiff.level(diff, warn, critical);
            String direction = ReserveDiff.direction(diff);
            boolean pass = diff == 0;

            ReserveCheckLogDO record = new ReserveCheckLogDO();
            record.setId("RR" + reserveCheckMapper.nextId());
            record.setBookBalance(book);
            record.setActualBalance(actual);
            record.setDiff(diff);
            record.setPass(pass);
            record.setCheckedAt(System.currentTimeMillis());
            record.setBizDate(day);
            record.setChannelCode(channel);
            record.setLevel(level);
            record.setDirection(direction);
            String disputeNo = null;
            if (!pass) {
                disputeNo = raiseDispute(day, channel, record, diff, level, direction);
                record.setDisputeNo(disputeNo);
            }
            reserveCheckMapper.insertLog(record);
            MetricsSupport.count("reserve_recon", "channel", channel, "level", level);
            if (!pass) {
                MetricsSupport.count("reserve_diff");
                log.error("[告警] AC-5 账实差异 bizDate={} channel={} level={} direction={} book={} actual={} "
                                + "diff={} bankAdjust={} billDiff={} checkId={} disputeNo={}",
                        AccountingDayClock.dayText(day), channel, level, direction, book, actual, diff,
                        bankAdjust, bill.amount, record.getId(), disputeNo);
            } else {
                log.info("AC-5 账实核对通过 bizDate={} channel={} book={} actual={} checkId={}",
                        AccountingDayClock.dayText(day), channel, book, actual, record.getId());
            }

            ReserveReconDTO.ChannelDetail detail = new ReserveReconDTO.ChannelDetail();
            detail.setChannelCode(channel);
            detail.setBookBalance(book);
            detail.setActualBalance(actual);
            detail.setDiff(diff);
            detail.setPass(pass);
            detail.setLevel(level);
            detail.setDirection(direction);
            detail.setBankAdjust(bankAdjust);
            detail.setBillDiffAdjust(bill.amount);
            detail.setBillCount(bill.count);
            detail.setSource(bill.ready && bill.count > 0 ? "MOCK_BANK+CHANNEL_BILL" : "MOCK_BANK");
            detail.setCheckLogId(record.getId());
            detail.setDisputeNo(disputeNo);
            detail.setMessage(bill.message);
            details.add(detail);
        }
        long actualTotal = book + totalAdjust;
        long diffTotal = book - actualTotal;
        ReserveReconDTO dto = new ReserveReconDTO();
        dto.setBizDate(day);
        dto.setBookBalance(book);
        dto.setActualBalance(actualTotal);
        dto.setDiff(diffTotal);
        dto.setPass(diffTotal == 0);
        dto.setLevel(ReserveDiff.level(diffTotal, warn, critical));
        dto.setDirection(ReserveDiff.direction(diffTotal));
        dto.setCheckedAt(System.currentTimeMillis());
        dto.setDetails(details);
        dto.setChannelBillReady(billReady);
        dto.setActualSource(billReady ? "模拟银行存管事件 + CH-4 渠道账单逐笔差异" : "模拟银行存管事件（CH-4 账单未就绪/无当日账单）");
        return dto;
    }

    public List<ReserveCheckDTO> logs(Long bizDate, String channelCode, int limit) {
        int safe = limit <= 0 ? 20 : Math.min(limit, 200);
        Long day = bizDate == null ? null : AccountingDayClock.dayStart(bizDate);
        String channel = channelCode == null || channelCode.isBlank() ? null : channelCode.trim();
        List<ReserveCheckDTO> out = new ArrayList<>();
        for (ReserveCheckLogDO row : reserveCheckMapper.selectLogsBy(day, channel, safe)) {
            out.add(toDTO(row));
        }
        return out;
    }

    private List<String> resolveChannels(List<String> channels) {
        Set<String> out = new LinkedHashSet<>();
        if (channels != null) {
            for (String c : channels) {
                if (c != null && !c.isBlank()) {
                    out.add(c.trim());
                }
            }
        }
        if (out.isEmpty()) {
            for (String c : dynamicConfig.get(ReserveDiff.KEY_CHANNELS, ReserveDiff.DEFAULT_CHANNELS).split(",")) {
                if (!c.isBlank()) {
                    out.add(c.trim());
                }
            }
        }
        if (out.isEmpty()) {
            out.add(ReserveDiff.MOCK_BANK);
        }
        return new ArrayList<>(out);
    }

    /** CH-4 账单逐笔金额差异归集（只读 facade，未就绪/异常一律降级为 0，不阻断核对）。 */
    private BillDiff channelBillDiff(String channelCode, long day) {
        try {
            var result = channelFacade.listBills(channelCode, AccountingDayClock.dayText(day));
            if (result == null || !result.isSuccess() || result.getData() == null) {
                return BillDiff.empty();
            }
            long amount = 0;
            long count = 0;
            for (ChannelBillDTO bill : result.getData()) {
                if (bill == null || bill.getId() == null) {
                    continue;
                }
                var detailResult = channelFacade.getBillDetail(bill.getId());
                if (detailResult == null || !detailResult.isSuccess() || detailResult.getData() == null) {
                    continue;
                }
                ChannelBillDTO detail = detailResult.getData();
                count++;
                if (detail.getDiffs() == null) {
                    continue;
                }
                for (ChannelBillDTO.Diff diff : detail.getDiffs()) {
                    if (!"AMOUNT_DIFF".equals(diff.getDiffType())) {
                        continue;
                    }
                    long billAmount = diff.getBillAmount() == null ? 0L : diff.getBillAmount();
                    long localAmount = diff.getLocalAmount() == null ? 0L : diff.getLocalAmount();
                    amount += billAmount - localAmount;
                }
            }
            return new BillDiff(amount, count, true, count == 0 ? "CH-4 无当日账单（降级模拟口径）" : "CH-4 账单 " + count + " 份");
        } catch (Exception e) {
            log.warn("AC-5 CH-4 渠道账单不可达，降级模拟银行口径 channel={} err={}", channelCode, e.toString());
            return new BillDiff(0L, 0L, false, "CH-4 不可达：" + e.getClass().getSimpleName());
        }
    }

    private record BillDiff(long amount, long count, boolean ready, String message) {
        static BillDiff empty() {
            return new BillDiff(0L, 0L, false, "模拟银行存管口径");
        }
    }

    /**
     * 差错单幂等登记：幂等键 {@code (RESERVE_CHECK, bizDate:channel)} 落地为差错平台自身的
     * {@code (source=RESERVE, bizNo=AC5:<bizDate>:<channel>, checkName)} 未关闭单去重 ——
     * 同一会计日同一渠道重复核对只返回同一张差错单，绝不重复建单。
     *
     * <p>不再套本地 IdempotentTemplate：其结果缓存会把一次建单失败固化成 null，
     * 导致该会计日差异永久丢失（资金红线：差异不丢）。建单失败直接抛出，下次核对自动重试。
     */
    private String raiseDispute(long day, String channel, ReserveCheckLogDO record, long diff,
                                String level, String direction) {
        String key = "AC5:" + day + ":" + channel;
        try {
            {
                DisputeCreateRequest request = new DisputeCreateRequest();
                request.setType("AMOUNT_MISMATCH");
                request.setSource("RESERVE");
                request.setBizNo(key);
                request.setPaymentId(null);
                request.setAmount(Math.abs(diff));
                request.setStrategy("MANUAL");
                request.setCheckName("AC-5 账实核对");
                // 差错平台 dispute_order.biz_date 为 VARCHAR(8)（yyyyMMdd 紧凑口径），不可传 yyyy-MM-dd
                request.setBizDate(AccountingDayClock.dayText(day).replace("-", ""));
                request.setReason("账实差异[" + level + "/" + direction + "] channel=" + channel
                        + "：SYS_CASH 账面 " + record.getBookBalance() + " vs 实际 " + record.getActualBalance()
                        + "，差额 " + diff + "（" + ("LONG".equals(direction) ? "长款" : "短款") + "）");
                var result = disputeFacade.create(request);
                if (result != null && result.isSuccess() && result.getData() != null) {
                    String disputeNo = result.getData().getDisputeNo();
                    log.warn("AC-5 账实差异已登记差错单 checkId={} disputeNo={} level={}", record.getId(), disputeNo, level);
                    return disputeNo;
                }
                // 建单失败必须抛出（不缓存 null），下次核对自动重试 ⇒ 差异不丢
                throw new IllegalStateException("差错单登记失败: " + result);
            }
        } catch (Exception e) {
            log.error("差错平台不可达，AC-5 账实差异仅告警留痕 checkId={} key={}", record.getId(), key, e);
            return null;
        }
    }

    static ReserveCheckDTO toDTO(ReserveCheckLogDO row) {
        ReserveCheckDTO dto = new ReserveCheckDTO();
        dto.setId(row.getId());
        dto.setBookBalance(row.getBookBalance());
        dto.setActualBalance(row.getActualBalance());
        dto.setDiff(row.getDiff());
        dto.setPass(row.getPass());
        dto.setCheckedAt(row.getCheckedAt());
        dto.setBizDate(row.getBizDate());
        dto.setChannelCode(row.getChannelCode());
        dto.setLevel(row.getLevel());
        dto.setDirection(row.getDirection());
        dto.setDisputeNo(row.getDisputeNo());
        return dto;
    }
}