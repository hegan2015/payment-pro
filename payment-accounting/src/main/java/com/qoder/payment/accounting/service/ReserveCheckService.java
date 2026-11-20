package com.qoder.payment.accounting.service;

import com.qoder.payment.accounting.dataobject.ReserveBankAdjustDO;
import com.qoder.payment.accounting.dataobject.ReserveCheckLogDO;
import com.qoder.payment.accounting.mapper.ReserveCheckMapper;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.payment.facade.accounting.ReserveAdjustRequest;
import com.qoder.payment.facade.accounting.ReserveCheckDTO;
import com.qoder.payment.facade.dispute.DisputeCreateRequest;
import com.qoder.payment.facade.dispute.DisputeFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * S-7 备付金账实核对（资金防线）。
 *
 * <p>账面：SYS_CASH 账务余额（复式记账权威）。实际：模拟银行存管余额（P0 雏形）——
 * 模拟银行对每笔现金指令同步执行（无未达账），叠加银行侧独立事件（reserve_bank_adjust：
 * 未达账/差错/费用，经 reserveAdjust 注入，只影响「实际」侧，绝不触碰账务凭证与余额）。
 * P1 AC-5 将以真实渠道账单/银行余额查询（CH-4）替换本模拟口径。
 *
 * <p>差异 → ERROR 告警日志 + payment_accounting_reserve_diff_total 指标 + reserve_check_log 留痕
 * + 差错单（X-1，source=RESERVE、bizNo=核对流水号，幂等去重）。差错平台不可达时只降级告警，
 * 不阻断账实核对本身；银行侧差异不臆断冲正，策略固定 MANUAL 待人工核销。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReserveCheckService {

    private final ReserveCheckMapper reserveCheckMapper;
    private final AccountingService accountingService;
    private final DynamicConfig dynamicConfig;

    @DubboReference(check = false, timeout = 3000, retries = 0)
    private DisputeFacade disputeFacade;

    /** 账实核对：每次核对必留痕；差异告警（ERROR 日志 + 指标）。 */
    public ReserveCheckDTO check() {
        long book = accountingService.balance(AccountingService.SYS_CASH);
        long actual = book + reserveCheckMapper.sumAdjust();
        long diff = book - actual;
        boolean pass = diff == 0;
        long now = System.currentTimeMillis();
        ReserveCheckLogDO record = new ReserveCheckLogDO();
        record.setId("RC" + reserveCheckMapper.nextId());
        record.setBookBalance(book);
        record.setActualBalance(actual);
        record.setDiff(diff);
        record.setPass(pass);
        record.setCheckedAt(now);
        // AC-5 additive 元数据：差异分级/长短款方向（book/actual/diff/pass 口径逐字未改）
        record.setLevel(ReserveDiff.level(diff,
                dynamicConfig.getLong(ReserveDiff.KEY_WARN, ReserveDiff.DEFAULT_WARN),
                dynamicConfig.getLong(ReserveDiff.KEY_CRITICAL, ReserveDiff.DEFAULT_CRITICAL)));
        record.setDirection(ReserveDiff.direction(diff));
        if (!pass) {
            record.setDisputeNo(raiseDispute(record, diff));
        }
        reserveCheckMapper.insertLog(record);
        MetricsSupport.count("reserve_check", "status", pass ? "pass" : "fail");
        if (!pass) {
            MetricsSupport.count("reserve_diff");
            log.error("备付金账实差异告警 checkId={} bookBalance={} actualBalance={} diff={}（SYS_CASH 账面 vs 模拟银行存管）",
                    record.getId(), book, actual, diff);
        } else {
            log.info("备付金账实核对通过 checkId={} bookBalance={} actualBalance={}", record.getId(), book, actual);
        }
        return toDTO(record);
    }

    public List<ReserveCheckDTO> logs(int limit) {
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 200);
        return reserveCheckMapper.selectLogs(safeLimit).stream().map(ReserveCheckService::toDTO).toList();
    }

    /** 模拟银行存管侧调整（带符号，只追加事件，不动账务），返回调整后即时核对结果。 */
    public ReserveCheckDTO adjust(ReserveAdjustRequest request) {
        if (request == null || request.getAmount() == null || request.getAmount() == 0
                || Math.abs(request.getAmount()) > MoneyUtil.MAX_AMOUNT) {
            throw new BizException(ResultCode.PARAM_ERROR, "amount 需为非 0 整数（分），绝对值 ≤ " + MoneyUtil.MAX_AMOUNT);
        }
        String reason = request.getReason() == null || request.getReason().isBlank() ? "模拟银行侧事件" : request.getReason();
        ReserveBankAdjustDO adjust = new ReserveBankAdjustDO();
        adjust.setId("RA" + reserveCheckMapper.nextId());
        adjust.setAmount(request.getAmount());
        adjust.setReason(reason);
        adjust.setCreatedAt(System.currentTimeMillis());
        // AC-5 additive：渠道维度（null 保持 P0 存量口径，sumAdjust() 仍全表求和）
        adjust.setChannelCode(request.getChannelCode() == null || request.getChannelCode().isBlank()
                ? null : request.getChannelCode().trim());
        reserveCheckMapper.insertAdjust(adjust);
        log.warn("备付金模拟银行侧调整 adjustId={} amount={} reason={}（只影响账实核对实际侧，不动账务）",
                adjust.getId(), adjust.getAmount(), reason);
        return check();
    }

    /** 差异登记差错单（幂等 by 核对流水号）；差错平台不可达只降级告警，不影响核对结果。 */
    private String raiseDispute(ReserveCheckLogDO record, long diff) {
        try {
            DisputeCreateRequest request = new DisputeCreateRequest();
            request.setType("AMOUNT_MISMATCH");
            request.setSource("RESERVE");
            request.setBizNo(record.getId());
            request.setAmount(Math.abs(diff));
            request.setStrategy("MANUAL");
            request.setReason("备付金账实差异：SYS_CASH 账面 " + record.getBookBalance()
                    + " vs 模拟银行存管 " + record.getActualBalance() + "，差额 " + diff);
            var result = disputeFacade.create(request);
            if (result != null && result.isSuccess() && result.getData() != null) {
                log.warn("备付金差异已登记差错单 checkId={} disputeNo={}", record.getId(), result.getData().getDisputeNo());
                return result.getData().getDisputeNo();
            }
            log.error("备付金差异登记差错单失败 checkId={} result={}", record.getId(), result);
            return null;
        } catch (Exception e) {
            log.error("差错平台不可达，备付金差异仅告警留痕 checkId={}", record.getId(), e);
            return null;
        }
    }

    private static ReserveCheckDTO toDTO(ReserveCheckLogDO record) {
        return ReserveReconService.toDTO(record);
    }
}