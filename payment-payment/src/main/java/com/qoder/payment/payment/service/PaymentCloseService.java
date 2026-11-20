package com.qoder.payment.payment.service;

import com.qoder.payment.common.db.ShardRowMirror;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.accounting.AccountingTccContext;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.facade.settle.SettleBatchDTO;
import com.qoder.payment.facade.settle.SettleFacade;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.TradeFacade;
import com.qoder.payment.facade.trade.TradeTccContext;
import com.qoder.payment.payment.convert.PaymentConverter;
import com.qoder.payment.payment.dataobject.CloseLogDO;
import com.qoder.payment.payment.dataobject.PaymentDO;
import com.qoder.payment.payment.dataobject.PaymentLegDO;
import com.qoder.payment.payment.mapper.CloseLogMapper;
import com.qoder.payment.payment.mapper.PaymentLegMapper;
import com.qoder.payment.payment.mapper.PaymentMapper;
import com.qoder.payment.payment.mapper.RefundMapper;
import com.qoder.payment.payment.mapper.ShareOrderMapper;
import com.qoder.tcc.GlobalStatus;
import com.qoder.tcc.TccCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * P-5 关闭/撤销（支付单侧；订单侧超时关闭属 G 泳道 T-4，边界不重叠）。
 *
 * <p><b>关闭（CLOSE）</b>：仅在途 PROCESSING 单可关。先经 TCC 互斥 CAS
 * （rollbackAsync：TRYING/WAITING→CANCELLING，与受理侧 commitAsync 只一方生效——
 * 主会话刚修复的 confirm/cancel 竞态守卫），回滚释放冻结/预留后再把支付单 CAS 成 CLOSED；
 * 若提交已胜出（CONFIRMING/CONFIRMED）则拒绝关闭，<b>绝不产生「CLOSED 但已过账」的悬挂单</b>。
 * 崩溃窗口由协调器 recover 续驱回滚（dtx 扫描不依赖支付单状态），资金必释放。
 *
 * <p><b>撤销（CANCEL）</b>：已 SUCCESS 的<b>当日未清算</b>交易全额反向过账，区别于退款
 * （退款可跨日/部分退、走 refund_record；撤销当日全额、不产生退款单、refunded_amount 不变）。
 * 守卫：SUCCESS + TRADE + BALANCE + refunded_amount=0 + 无在途退款 + 无分账 +
 * 当前自然日（Asia/Shanghai）内受理 + 无覆盖当日的结算批次。CARD 已扣款交易的渠道当日撤销接口
 * （adapter.close/void captured）未就绪 → 明确拒绝走退款（保守，不臆造渠道语义）。
 * 撤销经独立 TCC（PAY_CANCEL,paymentId）：账务反向分支（bizNo=CX:paymentId 确定性幂等，
 * Try 冻结商户净额防超额撤销）→ Confirm 反向过账 → 支付单 CAS SUCCESS→CANCELLED。
 *
 * <p>幂等：(PAY_CLOSE/PAY_CANCEL,paymentId) 语义——重复请求返回首次终态；
 * payment_close_log UNIQUE(payment_id,type) 留痕审计。
 *
 * <p><b>已知边界（报告申请项）</b>：撤销后订单释放可重付需 trade 域新增
 * PAID→可重付 能力（既有 closeOrder 仅 CREATED），本轮订单保持 PAID；
 * 对账仅核 SUCCESS/FAILED 单，CANCELLED/CLOSED 不参与核对（状态过滤），试算仍平衡。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCloseService {

    private final PaymentMapper paymentMapper;
    private final PaymentLegMapper legMapper;
    private final com.qoder.payment.payment.mapper.OutboxMapper outboxMapper;
    private final RefundMapper refundMapper;
    private final ShareOrderMapper shareOrderMapper;
    private final CloseLogMapper closeLogMapper;
    private final TccCoordinator coordinator;
    private final JdbcTemplate jdbc;
    private final com.qoder.payment.payment.config.TccParticipants participants;
    private final ObjectProvider<ShardRowMirror> shardRowMirror;

    @DubboReference
    private SettleFacade settleFacade;

    @DubboReference
    private TradeFacade tradeFacade;

    @DubboReference
    private com.qoder.payment.facade.accounting.AccountingFacade accountingFacade;

    // ------------------------------------------------------------------ 关闭

    public PaymentDTO close(String paymentId, String requestId) {
        PaymentDO payment = require(paymentId);
        if ("CLOSED".equals(payment.getStatus()) && "CLOSE".equals(payment.getCloseType())) {
            return PaymentConverter.toDTO(payment);
        }
        if (!"PROCESSING".equals(payment.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "仅在途支付单可关闭: " + payment.getStatus());
        }
        String xid = payment.getXid();
        if (xid == null || xid.isBlank()) {
            // begin 之前崩溃：分支从未注册、资金从未预留 → 直接 CAS 关闭（与补单同一判据）
            if (paymentMapper.closeProcessing(paymentId, System.currentTimeMillis()) == 1) {
                log(paymentId, "CLOSE", requestId, null);
                mirror(paymentId);
                return reload(paymentId);
            }
            return resolveCloseRace(paymentId, requestId, null);
        }
        GlobalStatus dtx = coordinator.status(xid);
        if (dtx == null) {
            if (paymentMapper.closeProcessing(paymentId, System.currentTimeMillis()) == 1) {
                log(paymentId, "CLOSE", requestId, xid);
                mirror(paymentId);
                return reload(paymentId);
            }
            return resolveCloseRace(paymentId, requestId, xid);
        }
        if (dtx == GlobalStatus.CONFIRMING || dtx == GlobalStatus.CONFIRMED) {
            throw new BizException(ResultCode.CONFLICT, "支付已提交，不可关闭（请待终态后退款）: " + dtx);
        }
        if (dtx == GlobalStatus.DEAD) {
            throw new BizException(ResultCode.CONFLICT, "支付事务异常（DEAD），请人工/差错介入后再关闭");
        }
        // TRYING/WAITING/CANCELLING：互斥 CAS 回滚（与受理 commitAsync 只一方生效）
        coordinator.rollbackAsync(xid, "支付关闭(P-5)");
        GlobalStatus after = coordinator.status(xid);
        if (after == GlobalStatus.CONFIRMING || after == GlobalStatus.CONFIRMED) {
            throw new BizException(ResultCode.CONFLICT, "支付已提交，不可关闭（竞态让位）: " + after);
        }
        long now = System.currentTimeMillis();
        if (paymentMapper.closeProcessing(paymentId, now) == 1) {
            log(paymentId, "CLOSE", requestId, xid);
            mirror(paymentId);
            log.info("支付关闭 paymentId={} xid={} dtx={}", paymentId, xid, after);
            return reload(paymentId);
        }
        return resolveCloseRace(paymentId, requestId, xid);
    }

    /** 关闭竞态收敛：回滚监听可能已把 PROCESSING 翻 FAILED，凭同一 xid 收敛为 CLOSED。 */
    private PaymentDTO resolveCloseRace(String paymentId, String requestId, String xid) {
        PaymentDO current = require(paymentId);
        if ("CLOSED".equals(current.getStatus())) {
            return PaymentConverter.toDTO(current);
        }
        if ("FAILED".equals(current.getStatus()) && xid != null) {
            GlobalStatus dtx = coordinator.status(xid);
            if (dtx == GlobalStatus.CANCELLING || dtx == GlobalStatus.CANCELLED) {
                if (paymentMapper.closeFailedAfterRollback(paymentId, xid, System.currentTimeMillis()) == 1) {
                    log(paymentId, "CLOSE", requestId, xid);
                    mirror(paymentId);
                    return reload(paymentId);
                }
            }
        }
        throw new BizException(ResultCode.CONFLICT, "支付单状态已变更，关闭让位: " + current.getStatus());
    }

    // ------------------------------------------------------------------ 当日撤销

    public PaymentDTO cancel(String paymentId, String requestId) {
        PaymentDO payment = require(paymentId);
        if ("CANCELLED".equals(payment.getStatus()) && "CANCEL".equals(payment.getCloseType())) {
            // P2-H：MERGE/STAGE 撤销后 trade 侧释放的崩溃窗口幂等收敛（重复撤销请求续驱）
            convergeRelease(payment);
            return PaymentConverter.toDTO(payment);
        }
        if (!"SUCCESS".equals(payment.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "仅已成功支付单可撤销: " + payment.getStatus());
        }
        if (!"TRADE".equals(payment.getBizType())) {
            throw new BizException(ResultCode.BIZ_ERROR, "资金单不支持撤销（请走退款）");
        }
        // P2-H T-5：合并+组合叠加单含渠道腿，当日撤销未就绪 → 明确走退款（保守不臆造渠道语义）
        if ("MERGE_COMBO".equals(payment.getPayMode())) {
            throw new BizException(ResultCode.BIZ_ERROR, "合并组合支付单请走退款（渠道当日撤销接口未就绪）");
        }
        if (!"BALANCE".equals(payment.getMethod())) {
            throw new BizException(ResultCode.BIZ_ERROR, "渠道当日撤销接口未就绪，CARD 交易请走退款");
        }
        if (payment.getRefundedAmount() != null && payment.getRefundedAmount() > 0) {
            throw new BizException(ResultCode.CONFLICT, "已退款交易不可撤销（撤销须全额未退）");
        }
        if (refundMapper.countProcessingByPayment(paymentId) > 0) {
            throw new BizException(ResultCode.CONFLICT, "存在在途退款，不可撤销");
        }
        if (shareOrderMapper.countByPaymentId(paymentId) > 0) {
            throw new BizException(ResultCode.CONFLICT, "已分账交易不可撤销（请先回退分账）");
        }
        // 当日未清算守卫：受理发生在当前自然日（Asia/Shanghai，渠道「当日撤销」语义，
        // 不依赖会计日标签——会计日可能因日切演练漂移，回正须拍板）+ 无覆盖当日的结算批次
        long todayStart = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        if (payment.getCreatedAt() == null || payment.getCreatedAt() < todayStart) {
            throw new BizException(ResultCode.CONFLICT, "非当日交易，不可撤销（请走退款）");
        }
        List<SettleBatchDTO> batches = settleFacade.listBatches(payment.getMerchantId(), null).orThrow();
        for (SettleBatchDTO batch : batches) {
            if (batch.getBizDate() != null && batch.getBizDate() >= todayStart) {
                throw new BizException(ResultCode.CONFLICT, "已清算批次覆盖当日，不可撤销（请走退款）");
            }
        }
        // P2-H T-5/T-6：MERGE/STAGE 撤销前置守卫（子订单/期维度守恒与可释放性）
        requireMergeStageCancellable(payment);

        String xid = driveCancelTransaction(payment);
        GlobalStatus dtx = coordinator.status(xid);
        if (dtx != GlobalStatus.CONFIRMED) {
            throw new BizException(ResultCode.CONFLICT, "撤销过账在途/未决（" + dtx + "），请稍后重试幂等收敛");
        }
        if (paymentMapper.cancelSuccess(paymentId, System.currentTimeMillis()) != 1) {
            PaymentDO current = require(paymentId);
            if ("CANCELLED".equals(current.getStatus())) {
                convergeRelease(current);
                return PaymentConverter.toDTO(current);
            }
            throw new BizException(ResultCode.CONFLICT, "支付单状态已变更（退款竞态守卫），撤销让位: " + current.getStatus());
        }
        log(paymentId, "CANCEL", requestId, xid);
        mirror(paymentId);
        // P2-H：撤销 CAS 胜出后释放 trade 侧（幂等；崩溃窗口由重复撤销请求 convergeRelease 续驱）
        convergeRelease(payment);
        log.info("当日撤销 paymentId={} xid={} amount={} fee={}", paymentId, xid, payment.getAmount(), payment.getFee());
        return reload(paymentId);
    }

    /** P2-H：MERGE 须合并单 PAID 且各子订单未退；STAGE 须最新已付期且订单未退（否则走退款）。 */
    private void requireMergeStageCancellable(PaymentDO payment) {
        if ("MERGE".equals(payment.getPayMode())) {
            if (payment.getMergeId() == null) {
                throw new BizException(ResultCode.BIZ_ERROR, "合并支付单缺合并单关联: " + payment.getId());
            }
            com.qoder.payment.facade.trade.MergeOrderDTO merge = tradeFacade.getMergeOrder(payment.getMergeId()).orThrow();
            if (!"PAID".equals(merge.getStatus())) {
                throw new BizException(ResultCode.CONFLICT, "合并单未支付完成，不可撤销: " + merge.getStatus());
            }
            for (com.qoder.payment.facade.trade.MergeRelDTO rel : merge.getRels()) {
                if (rel.getRefundedAmount() != null && rel.getRefundedAmount() > 0) {
                    throw new BizException(ResultCode.CONFLICT, "子订单已退款，合并单不可撤销（请走退款）: " + rel.getOrderId());
                }
                if (!"PAID".equals(rel.getOrderStatus())) {
                    throw new BizException(ResultCode.CONFLICT, "子订单状态异常，不可撤销: " + rel.getOrderId() + " " + rel.getOrderStatus());
                }
            }
        } else if ("STAGE".equals(payment.getPayMode())) {
            OrderDTO order = tradeFacade.getOrder(payment.getOrderId()).orThrow();
            int paidStage = order.getPaidStage() == null ? 0 : order.getPaidStage();
            if (payment.getStageSeq() == null || paidStage != payment.getStageSeq()) {
                throw new BizException(ResultCode.CONFLICT, "仅最新已付期可撤销: 当前已付 " + paidStage + " 期，本单第 " + payment.getStageSeq() + " 期");
            }
            if (order.getRefundedAmount() != null && order.getRefundedAmount() > 0) {
                throw new BizException(ResultCode.CONFLICT, "订单已退款，阶段单不可撤销（请走退款）");
            }
        }
    }

    /** P2-H：MERGE/STAGE 撤销后 trade 侧释放（幂等 CAS；SINGLE 单空操作，既有语义不变）。 */
    private void convergeRelease(PaymentDO payment) {
        if ("MERGE".equals(payment.getPayMode()) && payment.getMergeId() != null) {
            tradeFacade.releaseMergeAfterCancel(payment.getMergeId(), payment.getId()).orThrow();
        } else if ("STAGE".equals(payment.getPayMode())) {
            tradeFacade.releaseStageAfterCancel(payment.getOrderId(), payment.getId()).orThrow();
        }
    }

    /** 撤销 TCC：首次 begin；崩溃重试凭 (PAY_CANCEL,paymentId) 唯一键找回既有事务续驱。 */
    private String driveCancelTransaction(PaymentDO payment) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT xid, status FROM dtx_transaction WHERE biz_type='PAY_CANCEL' AND biz_no=?", payment.getId());
        if (!rows.isEmpty()) {
            String xid = (String) rows.get(0).get("xid");
            GlobalStatus status = GlobalStatus.valueOf((String) rows.get(0).get("status"));
            if (status == GlobalStatus.CANCELLED || status == GlobalStatus.DEAD) {
                throw new BizException(ResultCode.CONFLICT, "撤销事务已回滚/异常（" + status + "），请人工核查后重试");
            }
            if (status != GlobalStatus.CONFIRMED) {
                coordinator.drive(xid);
            }
            return xid;
        }
        String xid;
        try {
            xid = coordinator.begin("PAY_CANCEL", payment.getId());
        } catch (RuntimeException e) {
            // 并发撤销：唯一键冲突 → 找回既有事务续驱
            List<Map<String, Object>> again = jdbc.queryForList(
                    "SELECT xid FROM dtx_transaction WHERE biz_type='PAY_CANCEL' AND biz_no=?", payment.getId());
            if (again.isEmpty()) {
                throw e;
            }
            String existing = (String) again.get(0).get("xid");
            coordinator.drive(existing);
            return existing;
        }
        // 一阶段宽限（与 PaymentService.tryGrace 同一护栏）：防 recover 与在途 tryAll 竞态误回滚
        jdbc.update("UPDATE dtx_transaction SET next_retry_at=? WHERE xid=? AND status='TRYING'",
                System.currentTimeMillis() + 10_000L, xid);
        AccountingTccContext ctx = new AccountingTccContext();
        ctx.setBizNo("CX:" + payment.getId());
        ctx.setRefId(payment.getId());
        ctx.setSubject("当日撤销-" + payment.getId());
        ctx.setReversed(true);
        long net = payment.getAmount() - payment.getFee();
        List<TransferLineDTO> lines = new ArrayList<>();
        lines.add(line(payment.getMerchantAccountId(), "D", net));
        if (payment.getFee() > 0) {
            lines.add(line("SYS_FEE", "D", payment.getFee()));
        }
        lines.add(line(payment.getPayerAccountId(), "C", payment.getAmount()));
        ctx.setLines(lines);
        coordinator.registerBranch(xid, "accounting", "PAY", ctx);
        GlobalStatus preTry = coordinator.status(xid);
        if (preTry == GlobalStatus.CANCELLED || preTry == GlobalStatus.CANCELLING) {
            throw new BizException(ResultCode.CONFLICT, "撤销事务已回滚，受理让位: " + xid);
        }
        coordinator.tryAll(xid);
        coordinator.commitAsync(xid);
        return xid;
    }

    // ------------------------------------------------------------------ 关闭竞态悬挂修复（服务端兜底）

    /**
     * P-5 关闭竞态悬挂修复（幂等，服务端资金兜底）：历史版本「零分支抢跑回滚」窗口可能留下
     * 「支付单 CLOSED + dtx CANCELLED，但晚注册分支已 Try（冻结/订单 PAYING）」的悬挂——
     * 原事务 driveCancel 以零分支完成，参与者侧 TRY_OK 分支日志成为孤儿。
     * 受理侧防悬挂守卫（tryAll 前状态检查）已根治新增路径；本方法对存量悬挂<b>按原 xid/branchId
     * 直接驱动参与者 Cancel</b>（模板幂等：TRY_OK→执行解冻/释放→CANCELLED；已 CANCELLED→空操作），
     * 不触碰支付单终态、不新建事务、不重复过账。仅 BALANCE（CARD 渠道悬挂须人工核查渠道终态）；
     * 订单非 PAYING 指向本单时幂等空操作。
     */
    public PaymentDTO repairCloseRace(String paymentId) {
        PaymentDO payment = require(paymentId);
        if (!"CLOSED".equals(payment.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "仅 CLOSED 单可执行关闭竞态修复: " + payment.getStatus());
        }
        if (!"BALANCE".equals(payment.getMethod())) {
            throw new BizException(ResultCode.BIZ_ERROR, "CARD 关闭竞态悬挂须人工核查渠道终态");
        }
        OrderDTO order = tradeFacade.getOrder(payment.getOrderId()).orThrow();
        if (!"PAYING".equals(order.getStatus()) || !paymentId.equals(order.getPaymentId())) {
            log.info("关闭竞态修复幂等空操作（订单已释放/不指向本单）paymentId={} orderStatus={}", paymentId, order.getStatus());
            return PaymentConverter.toDTO(payment);
        }
        String xid = payment.getXid();
        GlobalStatus dtx = xid == null ? null : coordinator.status(xid);
        if (dtx != GlobalStatus.CANCELLED) {
            throw new BizException(ResultCode.CONFLICT, "原事务非 CANCELLED 终态（" + dtx + "），交由 recover 收敛，勿手工修复");
        }
        List<Map<String, Object>> branches = jdbc.queryForList(
                "SELECT branch_id, participant, action, context FROM dtx_branch WHERE xid=? ORDER BY seq", xid);
        for (Map<String, Object> b : branches) {
            com.qoder.tcc.TccBranchRequest req = new com.qoder.tcc.TccBranchRequest();
            req.setXid(xid);
            req.setBranchId((String) b.get("branch_id"));
            req.setAction((String) b.get("action"));
            req.setContext((String) b.get("context"));
            com.qoder.payment.facade.tcc.TccParticipantFacade participant =
                    participants.asMap().get((String) b.get("participant"));
            if (participant == null) {
                throw new BizException(ResultCode.BIZ_ERROR, "未注册参与者: " + b.get("participant"));
            }
            participant.cancel(req).orThrow();
        }
        OrderDTO after = tradeFacade.getOrder(payment.getOrderId()).orThrow();
        log.info("关闭竞态修复 paymentId={} xid={} branches={} orderStatus={}",
                paymentId, xid, branches.size(), after.getStatus());
        return PaymentConverter.toDTO(require(paymentId));
    }

    /**
     * P360 受控终态修复：历史 TCC confirm/cancel 竞态把「dtx 已 CONFIRMED、资金已划转」的单
     * 误置 FAILED。本方法在**严格前置校验**后仅做 FAILED→SUCCESS 的 CAS 翻状态，
     * **不新增/删除任何凭证、不动任何账户余额**（资金本就正确，错的是状态标签）。
     *
     * <p>前置（任一不满足即 409，绝不动状态）：
     * ① 单存在且 FAILED；② xid 非空且 dtx==CONFIRMED（权威依据）；
     * ③ 账务凭证齐全且 Σ借方==支付金额（COMBO 逐腿归集）；④ 订单 PAID 且指向本单。
     *
     * <p>幂等：翻状态后单为 SUCCESS，重复调用命中前置①失败返回 409，不重复翻状态/不重复审计。
     * 大额双人复核与二次认证由网关闸门承担（StepUpAuthInterceptor）。
     */
    public Map<String, Object> repairTerminal(String paymentId, String reason, String requestId, String operator) {
        PaymentDO payment = paymentMapper.selectById(paymentId);
        if (payment == null) {
            throw new BizException(ResultCode.NOT_FOUND, "支付单不存在: " + paymentId);
        }
        if (!"FAILED".equals(payment.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "仅 FAILED 单可执行受控终态修复: " + payment.getStatus());
        }
        String xid = payment.getXid();
        if (xid == null || xid.isBlank()) {
            throw new BizException(ResultCode.CONFLICT, "无分布式事务记录，不可判定权威终态: " + paymentId);
        }
        GlobalStatus dtx = coordinator.status(xid);
        if (dtx != GlobalStatus.CONFIRMED) {
            throw new BizException(ResultCode.CONFLICT,
                    "权威 dtx 非 CONFIRMED（" + dtx + "），不可修复为 SUCCESS: " + paymentId);
        }
        List<PaymentLegDO> legs = legMapper.selectByPaymentId(paymentId);
        long debit = 0;
        StringBuilder voucherBiz = new StringBuilder();
        if (legs.isEmpty()) {
            debit = voucherDebit(paymentId);
            voucherBiz.append(paymentId);
        } else {
            for (PaymentLegDO leg : legs) {
                debit += voucherDebit(leg.getId());
                if (voucherBiz.length() > 0) voucherBiz.append(',');
                voucherBiz.append(leg.getId());
            }
        }
        if (debit != payment.getAmount()) {
            throw new BizException(ResultCode.CONFLICT,
                    "凭证不齐或 Σ借方≠金额（" + debit + " vs " + payment.getAmount() + "），不可修复: " + paymentId);
        }
        OrderDTO order = tradeFacade.getOrder(payment.getOrderId()).orThrow();
        if (!"PAID".equals(order.getStatus()) || !paymentId.equals(order.getPaymentId())) {
            throw new BizException(ResultCode.CONFLICT,
                    "订单非 PAID 或不指向本单（" + order.getStatus() + "），不可修复: " + paymentId);
        }
        int flipped = paymentMapper.casRepairTerminal(paymentId);
        if (flipped != 1) {
            throw new BizException(ResultCode.CONFLICT, "终态修复让位（状态已变更）: " + paymentId);
        }
        mirror(paymentId);
        // 补发结算义务：历史 FAILED 标签曾抑制 SETTLE_PAY，翻正后须与正常支付成功同口径落 outbox，
        // 否则对账「支付-清结算一致」判 ONE_SIDED。只补结算事件，不动资金/凭证。
        com.qoder.payment.facade.settle.SettlePayRequest settle = new com.qoder.payment.facade.settle.SettlePayRequest();
        settle.setPaymentId(paymentId);
        settle.setOrderId(payment.getOrderId());
        settle.setMerchantId(payment.getMerchantId());
        settle.setAmount(payment.getAmount());
        settle.setFee(payment.getFee() == null ? 0L : payment.getFee());
        settle.setMethod(payment.getMethod());
        long now = System.currentTimeMillis();
        outboxMapper.insertRouted("SETTLE_PAY", paymentId,
                com.qoder.tcc.TccContextUtil.toJson(settle), now, now, null);
        log.warn("P360 受控终态修复 paymentId={} xid={} before=FAILED after=SUCCESS voucher={} operator={} requestId={} reason={}",
                paymentId, xid, voucherBiz, operator, requestId, reason);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", paymentId);
        out.put("before", "FAILED");
        out.put("after", "SUCCESS");
        out.put("authorityXid", xid);
        out.put("voucherBizNo", voucherBiz.toString());
        out.put("repaired", true);
        out.put("operator", operator);
        out.put("requestId", requestId);
        return out;
    }

    /** 单张凭证 Σ借方；凭证不存在返回 -1（触发前置③失败）。 */
    private long voucherDebit(String bizNo) {
        com.qoder.payment.facade.accounting.EntryQuery q = new com.qoder.payment.facade.accounting.EntryQuery();
        q.setBizNo(bizNo);
        q.setPage(1);
        q.setSize(200);
        com.qoder.payment.facade.common.PageDTO<com.qoder.payment.facade.accounting.EntryDTO> page =
                accountingFacade.entriesPage(q).orThrow();
        List<com.qoder.payment.facade.accounting.EntryDTO> items = page.getItems();
        if (items == null || items.isEmpty()) {
            return -1;
        }
        long sum = 0;
        for (com.qoder.payment.facade.accounting.EntryDTO e : items) {
            if ("D".equals(e.getDir())) sum += e.getAmount() == null ? 0 : e.getAmount();
        }
        return sum;
    }

    // ------------------------------------------------------------------ 内部

    private void log(String paymentId, String type, String requestId, String xid) {
        try {
            CloseLogDO entry = new CloseLogDO();
            entry.setId("CL" + closeLogMapper.nextId());
            entry.setPaymentId(paymentId);
            entry.setType(type);
            entry.setRequestId(requestId);
            entry.setBizNo(xid);
            entry.setCreatedAt(System.currentTimeMillis());
            closeLogMapper.insert(entry);
        } catch (DuplicateKeyException e) {
            log.debug("关闭/撤销留痕幂等命中 paymentId={} type={}", paymentId, type);
        }
    }

    private PaymentDO require(String paymentId) {
        PaymentDO payment = paymentMapper.selectById(paymentId);
        if (payment == null) {
            throw new BizException(ResultCode.NOT_FOUND, "支付单不存在: " + paymentId);
        }
        return payment;
    }

    private PaymentDTO reload(String paymentId) {
        return PaymentConverter.toDTO(require(paymentId));
    }

    private void mirror(String paymentId) {
        ShardRowMirror mirror = shardRowMirror.getIfAvailable();
        if (mirror != null) {
            mirror.mirror("payment", paymentId);
        }
    }

    private static TransferLineDTO line(String accountId, String dir, long amount) {
        TransferLineDTO line = new TransferLineDTO();
        line.setAccountId(accountId);
        line.setDir(dir);
        line.setAmount(amount);
        return line;
    }
}