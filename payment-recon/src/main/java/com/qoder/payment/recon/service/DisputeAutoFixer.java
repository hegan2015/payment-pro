package com.qoder.payment.recon.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.accounting.PostRequest;
import com.qoder.payment.facade.accounting.ReverseRequest;
import com.qoder.payment.facade.accounting.ReverseResultDTO;
import com.qoder.payment.facade.accounting.TransferDTO;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.payment.facade.accounting.TrialDTO;
import com.qoder.payment.facade.dispute.DisputeAutoFixResultDTO;
import com.qoder.payment.facade.dispute.DisputeDTO;
import com.qoder.payment.facade.notify.NotifyFacade;
import com.qoder.payment.facade.notify.NotifyRecordDTO;
import com.qoder.payment.recon.dataobject.DisputeOrderDO;
import com.qoder.payment.recon.mapper.DisputeOrderMapper;
import com.qoder.tcc.IdempotentTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * S-4 差错自动冲正/挂账策略执行器。
 *
 * <p>默认影子模式（recon.dispute.autofix=false，local/docker 双 profile 同默认）：只分类+建议策略不动账；
 * 开启（或人工 approve / autofix/run execute=true 显式授权）后按策略执行：
 * <ul>
 *   <li>AMOUNT_MISMATCH → {@code AccountingFacade.reverse} 红冲原凭证 + 蓝补正确金额（post 幂等）；</li>
 *   <li>ONE_SIDED/CHANNEL_MISSING/FEE_MISMATCH → {@code suspensePost} 挂账 SYS_SUSPENSE 待人工核销；</li>
 *   <li>NOTIFY_DEAD → {@code NotifyFacade.replay} 重放，送达后自动 RESOLVED；</li>
 *   <li>STATUS_MISMATCH/SOURCE_UNAVAILABLE → MANUAL，不强行冲正（资金安全保守）。</li>
 * </ul>
 *
 * <p>资金护航：修复幂等 (DISPUTE_FIX,disputeId) + 修复凭证 bizNo 确定性（DSPF/DSPB+disputeId，
 * 账务侧 REVERSE/SUSPENSE/POST 幂等吸收重复动账）；修复后必跑试算平衡（D=C 且 Σbalance=0），
 * 失败回滚状态并告警。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisputeAutoFixer {

    private static final String SYS_CASH = "SYS_CASH";
    private static final String SYS_SUSPENSE = "SYS_SUSPENSE";
    private static final String SYS_FEE = "SYS_FEE";

    private final DisputeOrderMapper disputeOrderMapper;
    private final DisputeService disputeService;
    private final IdempotentTemplate idempotentTemplate;

    @DubboReference
    private AccountingFacade accountingFacade;

    @DubboReference
    private NotifyFacade notifyFacade;

    /** S-4 灰度开关：默认 false=影子模式（只建议不动账）。 */
    @Value("${recon.dispute.autofix:false}")
    private boolean autofixEnabled;

    /** 人工 approve / 调度 autofix 统一入口（幂等）。 */
    public DisputeDTO fix(long disputeId, String operator) {
        DisputeOrderDO order = disputeService.require(disputeId);
        if (DisputeService.RESOLVED.equals(order.getStatus())
                || DisputeService.CLOSED.equals(order.getStatus())
                || DisputeService.IGNORED.equals(order.getStatus())) {
            return disputeService.toDTO(order, false); // 终态幂等 no-op
        }
        if (!DisputeClassifier.autoFixable(order.getStrategy())) {
            throw new BizException(ResultCode.CONFLICT,
                    "差错单策略 " + order.getStrategy() + " 不支持自动修复，请人工 resolve: " + disputeId);
        }
        if (DisputeClassifier.NOTIFY_REPLAY.equals(order.getStrategy())) {
            return fixNotify(order, operator);
        }
        try {
            return idempotentTemplate.execute("DISPUTE_FIX", String.valueOf(disputeId), DisputeDTO.class,
                    () -> doAccountingFix(disputeId, operator));
        } catch (RuntimeException e) {
            rollbackIfFixing(disputeId, operator, e.getMessage());
            throw e;
        }
    }

    /** 批量：execute=null 按配置开关；影子模式只返回建议策略的 OPEN 单，不动账。 */
    public DisputeAutoFixResultDTO runAll(Boolean execute, String operator) {
        boolean shadow = execute == null ? !autofixEnabled : !execute;
        List<DisputeOrderDO> open = disputeOrderMapper.selectOpenAutoFixable(100);
        DisputeAutoFixResultDTO result = new DisputeAutoFixResultDTO();
        result.setMode(shadow ? "SHADOW" : "EXECUTE");
        result.setTotal(open.size());
        for (DisputeOrderDO order : open) {
            if (shadow) {
                result.getItems().add(disputeService.toDTO(order, false));
                continue;
            }
            try {
                result.getItems().add(fix(order.getId(), operator));
                result.setFixed(result.getFixed() + 1);
            } catch (Exception e) {
                log.error("[告警] 差错自动修复失败 disputeNo={}: {}", order.getDisputeNo(), e.getMessage());
                result.getErrors().put(String.valueOf(order.getId()), e.getMessage());
            }
        }
        log.info("差错自动修复运行 mode={} total={} fixed={} errors={}",
                result.getMode(), result.getTotal(), result.getFixed(), result.getErrors().size());
        return result;
    }

    // ---------- 账务修复（幂等模板内） ----------

    private DisputeDTO doAccountingFix(long disputeId, String operator) {
        DisputeOrderDO order = disputeService.require(disputeId);
        String from = order.getStatus();
        if (!DisputeService.OPEN.equals(from) && !DisputeService.ASSIGNED.equals(from)
                && !DisputeService.FIXING.equals(from)) {
            throw new BizException(ResultCode.CONFLICT, "差错单状态 " + from + " 不允许修复");
        }
        disputeService.transition(disputeId, from, DisputeService.FIXING, "FIX_START", operator,
                "strategy=" + order.getStrategy());
        String fixBizNo;
        if (DisputeClassifier.REVERSE_AND_REPOST.equals(order.getStrategy())) {
            fixBizNo = reverseAndRepost(order);
        } else {
            fixBizNo = suspense(order);
        }
        // 修复后试算平衡校验（D=C 且 Σbalance=0），失败抛异常 → 本地事务回滚状态 + 外层告警
        TrialDTO trial = accountingFacade.trial().orThrow();
        if (!Boolean.TRUE.equals(trial.getBalanced()) || trial.getBalanceSum() != 0) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "修复后试算不平衡 D=" + trial.getTotalDebit() + " C=" + trial.getTotalCredit()
                            + " Σbalance=" + trial.getBalanceSum());
        }
        disputeService.markResolved(order, DisputeService.FIXING, "FIX_SUCCESS", operator,
                "fixBizNo=" + fixBizNo + " trial D=C=" + trial.getTotalDebit(), fixBizNo);
        DisputeService.countQuietly("dispute_fixed", "strategy", order.getStrategy());
        log.info("差错修复成功 disputeNo={} strategy={} fixBizNo={}", order.getDisputeNo(), order.getStrategy(), fixBizNo);
        return disputeService.toDTO(disputeService.require(disputeId), false);
    }

    /** 金额不一致：红冲原凭证（REVERSE 幂等）→ 蓝补正确金额（POST 幂等，bizNo 确定性）。 */
    private String reverseAndRepost(DisputeOrderDO order) {
        String origBizNo = order.getBizNo();
        TransferDTO orig = accountingFacade.transfer(origBizNo).orThrow();
        ReverseRequest reverse = new ReverseRequest();
        reverse.setOrigBizNo(origBizNo);
        reverse.setReason("差错修复 " + order.getDisputeNo());
        reverse.setRequestId("DSPF" + order.getId());
        ReverseResultDTO rv = accountingFacade.reverse(reverse).orThrow();

        long target = order.getAmount() == null ? 0L : order.getAmount();
        long origDebit = orig.getLines().stream().filter(l -> "D".equals(l.getDir()))
                .mapToLong(TransferLineDTO::getAmount).sum();
        List<TransferLineDTO> blue = blueLines(orig.getLines(), target);
        if (blue != null) {
            PostRequest post = new PostRequest();
            post.setBizNo("DSPB" + order.getId());
            post.setSubject("差错蓝补 " + order.getDisputeNo());
            post.setRefId(origBizNo);
            post.setLines(blue);
            accountingFacade.post(post).orThrow();
            return rv.getReverseBizNo() + "+DSPB" + order.getId();
        }
        // 无法构造平衡蓝补 → 差额挂账待人工（保守，不强行过账）
        long delta = target - origDebit;
        if (delta != 0) {
            String suspenseBizNo = suspensePost(order, Math.abs(delta), delta < 0);
            return rv.getReverseBizNo() + "+" + suspenseBizNo;
        }
        return rv.getReverseBizNo();
    }

    /**
     * 蓝补分录：原凭证结构不变，首个借方行与首个非手续费贷方行各加差额 delta=target-原借方合计，
     * 保持借贷平衡（手续费行不动）。金额为负/零或无法平衡时返回 null 走挂账兜底。
     */
    private static List<TransferLineDTO> blueLines(List<TransferLineDTO> origLines, long target) {
        if (target <= 0 || origLines == null || origLines.isEmpty()) {
            return null;
        }
        long origDebit = origLines.stream().filter(l -> "D".equals(l.getDir()))
                .mapToLong(TransferLineDTO::getAmount).sum();
        long delta = target - origDebit;
        List<TransferLineDTO> blue = new ArrayList<>();
        boolean debitAdjusted = false;
        boolean creditAdjusted = false;
        for (TransferLineDTO l : origLines) {
            long amount = l.getAmount();
            if (!debitAdjusted && "D".equals(l.getDir())) {
                amount += delta;
                debitAdjusted = true;
            } else if (!creditAdjusted && "C".equals(l.getDir()) && !SYS_FEE.equals(l.getAccountId())) {
                amount += delta;
                creditAdjusted = true;
            }
            if (amount <= 0) {
                return null;
            }
            TransferLineDTO copy = new TransferLineDTO();
            copy.setAccountId(l.getAccountId());
            copy.setDir(l.getDir());
            copy.setAmount(amount);
            blue.add(copy);
        }
        long d = blue.stream().filter(l -> "D".equals(l.getDir())).mapToLong(TransferLineDTO::getAmount).sum();
        long c = blue.stream().filter(l -> "C".equals(l.getDir())).mapToLong(TransferLineDTO::getAmount).sum();
        return debitAdjusted && creditAdjusted && d == c && d == target ? blue : null;
    }

    /** 单边账/渠道缺失/手续费差：差额挂账 SYS_SUSPENSE（长款 D SYS_CASH/C SYS_SUSPENSE，短款反之）。 */
    private String suspense(DisputeOrderDO order) {
        long amount = order.getAmount() == null ? 0L : order.getAmount();
        if (amount <= 0) {
            throw new BizException(ResultCode.CONFLICT, "挂账修复要求差错金额为正: " + order.getDisputeNo());
        }
        boolean shortSide = DisputeClassifier.CHANNEL_MISSING.equals(order.getType());
        return suspensePost(order, amount, shortSide);
    }

    private String suspensePost(DisputeOrderDO order, long amount, boolean shortSide) {
        String bizNo = "DSPF" + order.getId();
        PostRequest post = new PostRequest();
        post.setBizNo(bizNo);
        post.setSubject("差错挂账 " + order.getDisputeNo());
        post.setRefId(order.getBizNo());
        post.setLines(shortSide
                ? List.of(line(SYS_SUSPENSE, "D", amount), line(SYS_CASH, "C", amount))
                : List.of(line(SYS_CASH, "D", amount), line(SYS_SUSPENSE, "C", amount)));
        accountingFacade.suspensePost(post).orThrow();
        return bizNo;
    }

    private static TransferLineDTO line(String accountId, String dir, long amount) {
        TransferLineDTO l = new TransferLineDTO();
        l.setAccountId(accountId);
        l.setDir(dir);
        l.setAmount(amount);
        return l;
    }

    // ---------- 通知死信重放 ----------

    private DisputeDTO fixNotify(DisputeOrderDO order, String operator) {
        if (!DisputeService.FIXING.equals(order.getStatus())) {
            disputeService.transition(order.getId(), order.getStatus(), DisputeService.FIXING,
                    "APPROVE", operator, "通知死信重放 notifyId=" + order.getBizNo());
        }
        NotifyRecordDTO record = notifyFacade.replay(order.getBizNo()).orThrow();
        disputeService.writeLog(order.getId(), "REPLAY_TRIGGERED", operator,
                DisputeService.FIXING, DisputeService.FIXING,
                "notifyId=" + record.getId() + " status=" + record.getStatus());
        if ("DELIVERED".equals(record.getStatus())) {
            // 已送达（重放幂等 no-op）→ 直接核销
            disputeService.markResolved(order, DisputeService.FIXING, "REPLAY_DELIVERED", operator,
                    "通知已送达 notifyId=" + record.getId(), order.getFixBizNo());
        }
        // PENDING：送达后由 NotifyPusher 回调 resolveBySourceBiz 自动 RESOLVED
        return disputeService.toDTO(disputeService.require(order.getId()), false);
    }

    // ---------- 失败回滚 ----------

    private void rollbackIfFixing(long disputeId, String operator, String reason) {
        try {
            DisputeOrderDO current = disputeOrderMapper.selectById(disputeId);
            if (current != null && DisputeService.FIXING.equals(current.getStatus())) {
                disputeService.transition(disputeId, DisputeService.FIXING, DisputeService.OPEN,
                        "FIX_FAIL", operator, reason);
                DisputeService.countQuietly("dispute_fix_failed");
                log.error("[告警] 差错修复失败已回滚 OPEN disputeId={}: {}", disputeId, reason);
            }
        } catch (Exception e) {
            log.error("[告警] 差错修复回滚异常 disputeId={}", disputeId, e);
        }
    }
}