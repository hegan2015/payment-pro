package com.qoder.payment.channel.sign;

import com.qoder.payment.channel.dataobject.SignContractDO;
import com.qoder.payment.channel.dataobject.SignDeductDO;
import com.qoder.payment.channel.mapper.SignContractMapper;
import com.qoder.payment.channel.mapper.SignDeductMapper;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.account.LimitCheckDTO;
import com.qoder.payment.facade.payment.PayRequest;
import com.qoder.payment.facade.payment.PayResultDTO;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.facade.payment.PaymentFacade;
import com.qoder.payment.facade.sign.AgentPayRequest;
import com.qoder.payment.facade.sign.SignDeductDTO;
import com.qoder.payment.facade.trade.OrderCreateRequest;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.TradeFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * CH-6 代扣执行编排（<b>资金红线：不新造资金路径</b>）。
 *
 * <p>链路：授权校验（协议状态/有效期/扣款日/商户范围/单笔限额/周期累计限额/账户状态与限额）
 * → 落扣款单（INIT，request_id UNIQUE 幂等）→ {@code TradeFacade.createOrder}（outTradeNo=deductNo 幂等）
 * → {@code PaymentFacade.pay(method=CARD, requestId=deductNo:A<n>)} → 既有受理路径
 * （风控 → 手续费快照 → TCC 协调 accounting/channel/trade 分支 → 复式记账 → outbox 清结算/PAY_SUCCESS 通知）。
 * 本域<b>绝不动账、绝不绕过幂等</b>，只登记扣款单生命周期与重试策略。
 *
 * <p><b>拒绝在受理前不落悬挂单</b>：授权/额度/有效期/扣款日/账户校验全部在建交易单与支付单之前完成，
 * 拒绝只留 sign_audit REJECT 痕，不产生 sign_deduct / trade_order / payment / channel_trade 任何行。
 *
 * <p><b>额度并发护栏</b>：周期累计复核在 {@code SELECT ... FOR UPDATE} 协议行锁内执行，
 * 已用额度含在途单（INIT/PROCESSING），杜绝并发/重试窗口超扣。
 *
 * <p><b>失败重试与终止</b>：业务失败（渠道/风控拒绝）→ FAILED + 线性退避排期；
 * attempts ≥ maxRetry 或协议解约/到期/超额度 → TERMINATED（不再重试）。
 * <b>受理状态未知（RPC 超时/异常）一律不臆断失败</b>：留 PROCESSING，由补单收敛按支付单真实终态判定，
 * 重试前必须确认原支付单已终态，杜绝重复扣款。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignDeductService {

    private final SignContractMapper contractMapper;
    private final SignDeductMapper deductMapper;
    private final SignContractService contracts;
    private final SignAuditService audit;
    private final SignTx tx;
    private final DynamicConfig config;

    @DubboReference(check = false, timeout = 8000, retries = 0)
    private TradeFacade tradeFacade;

    /** 代扣经既有支付受理路径：TCC 全链路可能跨多域，超时放宽且 retries=0（资金绝不自动重发）。 */
    @DubboReference(check = false, timeout = 30000, retries = 0)
    private PaymentFacade paymentFacade;

    @DubboReference(check = false, timeout = 5000, retries = 0)
    private AccountFacade accountFacade;

    // ================= 代扣受理 =================

    public SignDeductDTO agentPay(AgentPayRequest request) {
        contracts.requireEnabled();
        if (request == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "代扣请求不可为空");
        }
        String signNo = trim(request.getSignNo());
        String requestId = trim(request.getRequestId());
        if (signNo == null || requestId == null || request.getAmount() == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "代扣必填 signNo/amount/requestId");
        }
        long amount = MoneyUtil.requireValid(request.getAmount());
        SignDeductDO replay = deductMapper.selectByRequestId(requestId);
        if (replay != null) {
            log.info("CH-6 代扣幂等命中 requestId={} deductNo={} status={}", requestId, replay.getDeductNo(),
                    replay.getStatus());
            return toDTO(replay, true);
        }
        SignContractDO contract = contracts.require(signNo);

        // ---- 受理前校验（任何一项拒绝都不落扣款单/交易单/支付单）----
        try {
            contracts.assertDeductable(contract, amount, request.getMerchantId(), true);
            assertAccount(contract.getAccountId(), amount);
        } catch (BizException e) {
            audit.writeQuietly(signNo, null, "REJECT", request.getOperator(), requestId, amount, "REJECT",
                    e.getMessage());
            log.warn("CH-6 代扣受理前拒绝 signNo={} requestId={} amount={} reason={}", signNo, requestId, amount,
                    e.getMessage());
            throw e;
        }

        // ---- 事务：协议行锁 + 周期额度复核 + 扣款单落库（INIT）----
        SignDeductDO deduct;
        try {
            deduct = tx.execute(() -> {
                SignContractDO locked = contractMapper.selectBySignNoForUpdate(signNo);
                if (locked == null) {
                    throw new BizException(ResultCode.NOT_FOUND, "签约协议不存在: " + signNo);
                }
                contracts.assertDeductable(locked, amount, request.getMerchantId(), true);
                contracts.assertPeriodQuota(locked, amount);
                SignDeductDO concurrent = deductMapper.selectByRequestId(requestId);
                if (concurrent != null) {
                    return concurrent;
                }
                long now = System.currentTimeMillis();
                SignDeductDO row = new SignDeductDO();
                row.setDeductNo("SD" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
                row.setSignNo(signNo);
                row.setAccountId(locked.getAccountId());
                row.setMerchantId(locked.getMerchantId());
                row.setRequestId(requestId);
                row.setAmount(amount);
                row.setStatus("INIT");
                row.setAttempts(0);
                row.setMaxRetry(locked.getMaxRetry() == null ? 0 : locked.getMaxRetry());
                row.setPeriodKey(SignContractService.periodKey(locked.getPeriodType(), now));
                row.setChannelCode(locked.getChannelCode());
                row.setCreatedAt(now);
                row.setUpdatedAt(now);
                deductMapper.insert(row);
                audit.write(signNo, row.getDeductNo(), "AGENT_PAY", request.getOperator(), requestId, amount, "OK",
                        "代扣受理（授权与额度校验通过）period=" + row.getPeriodKey());
                return row;
            });
        } catch (BizException e) {
            // 行锁内复核拒绝（并发额度/状态变化）：事务回滚不留扣款单，只留 REJECT 审计痕
            audit.writeQuietly(signNo, null, "REJECT", request.getOperator(), requestId, amount, "REJECT",
                    e.getMessage());
            log.warn("CH-6 代扣额度复核拒绝 signNo={} requestId={} amount={} reason={}", signNo, requestId, amount,
                    e.getMessage());
            throw e;
        }
        if (!"INIT".equals(deduct.getStatus())) {
            return toDTO(deduct, true);
        }
        return executeAttempt(contract, deduct, 1, request.getSubject(), request.getCallbackUrl(),
                request.getOperator());
    }

    /**
     * 执行一次扣款尝试（受理）：建交易单（幂等）→ CAS 绑定尝试 → 既有支付受理路径。
     * 每次尝试 requestId 确定性 {@code deductNo:A<n>} ⇒ 支付侧 request_id UNIQUE 天然幂等。
     */
    private SignDeductDTO executeAttempt(SignContractDO contract, SignDeductDO deduct, int attemptNo,
                                         String subject, String callbackUrl, String operator) {
        String deductNo = deduct.getDeductNo();
        String attemptRequestId = deductNo + ":A" + attemptNo;
        long now = System.currentTimeMillis();
        String orderId = deduct.getOrderId();
        String paymentId = null;
        try {
            if (orderId == null) {
                OrderCreateRequest oc = new OrderCreateRequest();
                oc.setMerchantId(contract.getMerchantId());
                oc.setSubject(subject == null || subject.isBlank() ? "代扣-" + contract.getSignNo() : subject);
                oc.setAmount(deduct.getAmount());
                // 商户侧幂等：同扣款单重试复用同一交易单（失败 TCC Cancel 已释放订单可重付）
                oc.setOutTradeNo(deductNo);
                oc.setBizType("TRADE");
                oc.setProductType("INSTANT");
                OrderDTO order = tradeFacade.createOrder(oc).orThrow();
                orderId = order.getId();
            } else {
                OrderDTO order = tradeFacade.getOrder(orderId).orThrow();
                if (!"CREATED".equals(order.getStatus())) {
                    throw new BizException(ResultCode.CONFLICT, "交易单状态不可再支付: " + order.getStatus());
                }
            }
            int bound = deductMapper.bindAttempt(deductNo, deduct.getStatus(), orderId, attemptRequestId,
                    attemptNo, null, now);
            if (bound == 0) {
                log.warn("CH-6 代扣尝试让位（并发/状态已变）deductNo={} attempt={}", deductNo, attemptNo);
                return toDTO(requireDeduct(deductNo), true);
            }

            PayRequest pay = new PayRequest();
            pay.setOrderId(orderId);
            pay.setMethod("CARD");
            pay.setCardNo(contracts.cardPlain(contract));
            pay.setRequestId(attemptRequestId);
            pay.setCallbackUrl(callbackUrl != null && !callbackUrl.isBlank() ? callbackUrl : contract.getCallbackUrl());
            PayResultDTO result = paymentFacade.pay(pay).orThrow();
            PaymentDTO payment = result.getPayment();
            paymentId = payment == null ? null : payment.getId();
            String status = payment == null ? null : payment.getStatus();
            log.info("CH-6 代扣经既有支付受理路径 deductNo={} attempt={} paymentId={} paymentStatus={} requestId={}",
                    deductNo, attemptNo, paymentId, status, attemptRequestId);
            if ("SUCCESS".equals(status) || "REFUNDED".equals(status)) {
                return finishSuccess(deductNo, paymentId, operator);
            }
            if ("FAILED".equals(status) || "CLOSED".equals(status)) {
                return finishFailed(deduct, attemptNo, paymentId,
                        payment.getFailReason() == null ? "支付单终态 " + status : payment.getFailReason(), operator);
            }
            // PROCESSING/WAITING（异步渠道）：资金在途，留 PROCESSING 交补单收敛，绝不重复受理
            deductMapper.noteProcessing(deductNo, paymentId, "渠道异步受理，等待终态回执/补单收敛", now);
            return toDTO(requireDeduct(deductNo), false);
        } catch (BizException e) {
            // 业务失败（渠道拒绝/风控拒绝/账务账户校验拒绝）：留 FAILED 痕 + 重试排期，不悬挂。
            // pay 抛出时支付单可能已落库并置 FAILED（受理后才失败）→ 回查绑定 paymentId，保证单据双向可追溯。
            if (paymentId == null) {
                paymentId = findPaymentIdQuietly(attemptRequestId);
            }
            log.warn("CH-6 代扣业务失败 deductNo={} attempt={} paymentId={} reason={}", deductNo, attemptNo,
                    paymentId, e.getMessage());
            return finishFailed(deduct, attemptNo, paymentId, e.getMessage(), operator);
        } catch (RuntimeException e) {
            // 基础设施异常/超时：受理状态未知 → 不臆断失败，留 PROCESSING 由补单按支付单真实终态判定
            log.error("[告警] CH-6 代扣受理状态未知 deductNo={} attempt={}: {}", deductNo, attemptNo, e.getMessage());
            deductMapper.bindAttempt(deductNo, deduct.getStatus(), orderId, attemptRequestId, attemptNo, null,
                    System.currentTimeMillis());
            deductMapper.noteProcessing(deductNo, null,
                    "受理状态未知（" + e.getClass().getSimpleName() + "），交补单收敛判定", System.currentTimeMillis());
            audit.writeQuietly(contract.getSignNo(), deductNo, "RETRY", operator, attemptRequestId,
                    deduct.getAmount(), "FAIL", "受理状态未知，留 PROCESSING 待收敛: " + e.getMessage());
            return toDTO(requireDeduct(deductNo), false);
        }
    }

    // ================= 重试 / 终止 / 收敛 =================

    /** 手动重试（与作业同一实现，幂等）：requestId 只作审计键，扣款单幂等键仍为其自身 request_id。 */
    public SignDeductDTO retry(String deductNo, String requestId, String operator) {
        SignDeductDO deduct = requireDeduct(deductNo);
        return retryInternal(deduct, operator, requestId);
    }

    private SignDeductDTO retryInternal(SignDeductDO deduct, String operator, String requestId) {
        String deductNo = deduct.getDeductNo();
        if ("SUCCESS".equals(deduct.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "扣款已成功，不可重试: " + deductNo);
        }
        if ("TERMINATED".equals(deduct.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "扣款已终止，不可重试: " + deductNo);
        }
        if ("PROCESSING".equals(deduct.getStatus())) {
            SignDeductDTO converged = converge(deduct);
            SignDeductDO after = requireDeduct(deductNo);
            if ("PROCESSING".equals(after.getStatus())) {
                log.info("CH-6 扣款在途未终态，不重复受理 deductNo={}", deductNo);
                return converged;
            }
            deduct = after;
            if (!"FAILED".equals(deduct.getStatus())) {
                return toDTO(deduct, false);
            }
        }
        SignContractDO contract = contracts.require(deduct.getSignNo());
        int nextAttempt = (deduct.getAttempts() == null ? 0 : deduct.getAttempts()) + 1;
        if (deduct.getMaxRetry() != null && nextAttempt > deduct.getMaxRetry() + 1) {
            return terminateInternal(deduct, "重试耗尽（attempts=" + deduct.getAttempts()
                    + " maxRetry=" + deduct.getMaxRetry() + "）", operator);
        }
        // 重试前重新校验授权与额度（解约/到期/超额度 → 终止，绝不继续扣款）；扣款日不复核（原单已在扣款日受理）
        final long retryAmount = deduct.getAmount();
        try {
            assertAccount(contract.getAccountId(), retryAmount);
            tx.run(() -> {
                SignContractDO locked = contractMapper.selectBySignNoForUpdate(contract.getSignNo());
                if (locked == null) {
                    throw new BizException(ResultCode.NOT_FOUND, "签约协议不存在: " + contract.getSignNo());
                }
                contracts.assertDeductable(locked, retryAmount, null, false);
                contracts.assertPeriodQuota(locked, retryAmount);
            });
        } catch (BizException e) {
            return terminateInternal(deduct, "重试前授权校验未通过，终止: " + e.getMessage(), operator);
        }
        // 原支付单必须已终态（防重复扣款）；未终态先经既有补单入口收敛
        SignDeductDO prior = requireDeduct(deductNo);
        if ("PROCESSING".equals(prior.getStatus())) {
            return toDTO(prior, false);
        }
        audit.writeQuietly(contract.getSignNo(), deductNo, "RETRY", operator, requestId, deduct.getAmount(), "OK",
                "第 " + nextAttempt + " 次尝试");
        return executeAttempt(contract, prior, nextAttempt, null, null, operator);
    }

    /** 人工终止（SUCCESS 不可终止：资金已成功不得抹除）。 */
    public SignDeductDTO terminate(String deductNo, String operator, String reason) {
        SignDeductDO deduct = requireDeduct(deductNo);
        if ("SUCCESS".equals(deduct.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "扣款已成功，不可终止: " + deductNo);
        }
        return terminateInternal(deduct, reason == null || reason.isBlank() ? "人工终止" : reason, operator);
    }

    private SignDeductDTO terminateInternal(SignDeductDO deduct, String reason, String operator) {
        long now = System.currentTimeMillis();
        int updated = deductMapper.casTerminate(deduct.getDeductNo(), SignAuditService.truncate(reason, 500), now);
        if (updated > 0) {
            audit.writeQuietly(deduct.getSignNo(), deduct.getDeductNo(), "TERMINATE", operator,
                    deduct.getRequestId(), deduct.getAmount(), "OK", reason);
            log.warn("CH-6 代扣终止 deductNo={} signNo={} attempts={} reason={}", deduct.getDeductNo(),
                    deduct.getSignNo(), deduct.getAttempts(), reason);
        }
        return toDTO(requireDeduct(deduct.getDeductNo()), updated == 0);
    }

    /**
     * 在途扣款单收敛（补单）：按支付单真实终态判定，绝不臆断失败/成功。
     * 支付单不存在 ⇒ 受理未成功，可安全转 FAILED 重试；在途 ⇒ 调既有 {@code PaymentFacade.reconverge}。
     */
    private SignDeductDTO converge(SignDeductDO deduct) {
        String deductNo = deduct.getDeductNo();
        PaymentDTO payment = null;
        try {
            if (deduct.getPaymentId() != null && !deduct.getPaymentId().isBlank()) {
                payment = orNull(paymentFacade.getPayment(deduct.getPaymentId()));
            }
            if (payment == null && deduct.getAttemptRequestId() != null) {
                payment = findByRequestId(deduct.getAttemptRequestId());
            }
        } catch (RuntimeException e) {
            log.warn("CH-6 代扣补单查询支付域不可达 deductNo={}: {}", deductNo, e.getMessage());
            return toDTO(deduct, false);
        }
        long now = System.currentTimeMillis();
        if (payment == null) {
            if (deduct.getOrderId() == null) {
                // 交易单都未建成：受理确未发生，转 FAILED 可重试
                return finishFailed(deduct, Math.max(1, deduct.getAttempts() == null ? 1 : deduct.getAttempts()),
                        null, "支付单未落库（受理未成功）", "system");
            }
            deductMapper.noteProcessing(deductNo, null, "支付单查询未命中，下次补单再判定", now);
            return toDTO(requireDeduct(deductNo), false);
        }
        String status = payment.getStatus();
        if ("SUCCESS".equals(status) || "REFUNDED".equals(status)) {
            return finishSuccess(deductNo, payment.getId(), "system");
        }
        if ("FAILED".equals(status) || "CLOSED".equals(status)) {
            return finishFailed(deduct, Math.max(1, deduct.getAttempts() == null ? 1 : deduct.getAttempts()),
                    payment.getId(), payment.getFailReason() == null ? "支付单终态 " + status
                            : payment.getFailReason(), "system");
        }
        // 在途：经既有补单入口驱动收敛（幂等，不重复过账）
        try {
            PaymentDTO driven = orNull(paymentFacade.reconverge(payment.getId()));
            if (driven != null && "SUCCESS".equals(driven.getStatus())) {
                return finishSuccess(deductNo, driven.getId(), "system");
            }
            if (driven != null && ("FAILED".equals(driven.getStatus()) || "CLOSED".equals(driven.getStatus()))) {
                return finishFailed(deduct, Math.max(1, deduct.getAttempts()), driven.getId(),
                        driven.getFailReason() == null ? "补单收敛终态 " + driven.getStatus() : driven.getFailReason(),
                        "system");
            }
        } catch (RuntimeException e) {
            log.warn("CH-6 代扣补单驱动失败 deductNo={} paymentId={}: {}", deductNo, payment.getId(), e.getMessage());
        }
        return toDTO(requireDeduct(deductNo), false);
    }

    /**
     * 重试/收敛作业入口（JobHandler {@code signDeductRetry}）：
     * ① 在途收敛 ② 到期失败重试 ③ 重试耗尽终止 ④ 协议到期置 EXPIRED。返回处理笔数。
     */
    public int runRetry(int limit) {
        int batch = limit > 0 ? limit : config.getInt(SignConfigKeys.RETRY_BATCH_SIZE,
                SignConfigKeys.DEFAULT_RETRY_BATCH_SIZE);
        long now = System.currentTimeMillis();
        int handled = 0;
        for (SignDeductDO row : deductMapper.selectProcessing(batch)) {
            converge(row);
            handled++;
        }
        for (SignDeductDO row : deductMapper.selectRetryDue(now, batch)) {
            try {
                retryInternal(row, "system", null);
            } catch (BizException e) {
                log.warn("CH-6 代扣重试作业跳过 deductNo={}: {}", row.getDeductNo(), e.getMessage());
            }
            handled++;
        }
        for (SignDeductDO row : deductMapper.selectExhausted(batch)) {
            terminateInternal(row, "重试耗尽（attempts=" + row.getAttempts() + " maxRetry=" + row.getMaxRetry() + "）",
                    "system");
            handled++;
        }
        handled += contracts.expireDue(batch);
        return handled;
    }

    // ================= 查询 =================

    public SignDeductDTO get(String deductNo) {
        return toDTO(requireDeduct(deductNo), false);
    }

    public List<SignDeductDTO> list(String signNo, String status) {
        List<SignDeductDTO> out = new ArrayList<>();
        for (SignDeductDO row : deductMapper.selectList(trim(signNo), trim(status), SignConfigKeys.LIST_LIMIT)) {
            out.add(toDTO(row, false));
        }
        return out;
    }

    public SignDeductDO requireDeduct(String deductNo) {
        if (deductNo == null || deductNo.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "deductNo 必填");
        }
        SignDeductDO row = deductMapper.selectByDeductNo(deductNo.trim());
        if (row == null) {
            throw new BizException(ResultCode.NOT_FOUND, "代扣单不存在: " + deductNo);
        }
        return row;
    }

    // ================= 内部工具 =================

    private SignDeductDTO finishSuccess(String deductNo, String paymentId, String operator) {
        long now = System.currentTimeMillis();
        deductMapper.finish(deductNo, "SUCCESS", paymentId, null, null, now, now);
        SignDeductDO row = requireDeduct(deductNo);
        audit.writeQuietly(row.getSignNo(), deductNo, "AGENT_PAY", operator, row.getRequestId(), row.getAmount(),
                "OK", "代扣成功 paymentId=" + paymentId + "（经 TCC + 复式记账）");
        log.info("CH-6 代扣成功 deductNo={} signNo={} amount={} paymentId={}", deductNo, row.getSignNo(),
                row.getAmount(), paymentId);
        return toDTO(row, false);
    }

    private SignDeductDTO finishFailed(SignDeductDO deduct, int attemptNo, String paymentId, String reason,
                                       String operator) {
        String deductNo = deduct.getDeductNo();
        long now = System.currentTimeMillis();
        int maxRetry = deduct.getMaxRetry() == null ? 0 : deduct.getMaxRetry();
        boolean exhausted = attemptNo > maxRetry;
        String status = exhausted ? "TERMINATED" : "FAILED";
        long interval = retryInterval(deduct);
        Long nextRetryAt = exhausted ? null : now + interval * attemptNo;
        Long finishedAt = exhausted ? now : null;
        int updated = deductMapper.finish(deductNo, status, paymentId, SignAuditService.truncate(reason, 500),
                nextRetryAt, finishedAt, now);
        if (updated == 0) {
            // 受理前失败（交易单/支付单尚未建成，扣款单仍 INIT）：同样落终态痕，绝不留 INIT 悬挂单
            updated = deductMapper.failInit(deductNo, status, SignAuditService.truncate(reason, 500), attemptNo,
                    nextRetryAt, finishedAt, now);
        }
        if (updated == 0) {
            // CAS 未命中（已被并发收敛/终止）：以库内真实状态为准，不覆盖
            return toDTO(requireDeduct(deductNo), true);
        }
        audit.writeQuietly(deduct.getSignNo(), deductNo, exhausted ? "TERMINATE" : "RETRY", operator,
                deduct.getRequestId(), deduct.getAmount(), "FAIL",
                "第 " + attemptNo + " 次尝试失败: " + reason + (exhausted ? "（重试耗尽终止）"
                        : "（下次重试 " + nextRetryAt + "）"));
        log.warn("CH-6 代扣失败 deductNo={} attempt={} status={} reason={}", deductNo, attemptNo, status, reason);
        return toDTO(requireDeduct(deductNo), false);
    }

    private long retryInterval(SignDeductDO deduct) {
        SignContractDO contract = contractMapper.selectBySignNo(deduct.getSignNo());
        if (contract != null && contract.getRetryIntervalMs() != null) {
            return contract.getRetryIntervalMs();
        }
        return config.getLong(SignConfigKeys.DEFAULT_RETRY_INTERVAL_MS, SignConfigKeys.DEFAULT_RETRY_INTERVAL_MS_VALUE);
    }

    /** 账户状态与单笔限额（只调 account 域既有 facade；不可达时不阻断，支付受理路径另有风控兜底）。 */
    private void assertAccount(String accountId, long amount) {
        AccountDTO account = accountFacade.get(accountId).orThrow();
        if (!"ACTIVE".equals(account.getStatus())) {
            throw new BizException(ResultCode.BIZ_ERROR, "付款账户非 ACTIVE，代扣拒绝: " + accountId);
        }
        try {
            Result<LimitCheckDTO> limit = accountFacade.checkLimit(accountId, amount, "SINGLE");
            if (limit != null && limit.isSuccess() && limit.getData() != null
                    && Boolean.FALSE.equals(limit.getData().getPass())) {
                throw new BizException(ResultCode.BIZ_ERROR, "账户单笔限额校验未通过: "
                        + limit.getData().getReason());
            }
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("CH-6 账户限额校验不可达（支付受理路径风控兜底）accountId={}: {}", accountId, e.getMessage());
        }
    }

    /** 按尝试幂等键回查支付单号（失败留痕绑定用；查询不可达时返回 null，绝不掩盖原始失败原因）。 */
    private String findPaymentIdQuietly(String attemptRequestId) {
        try {
            PaymentDTO payment = findByRequestId(attemptRequestId);
            return payment == null ? null : payment.getId();
        } catch (RuntimeException e) {
            log.warn("CH-6 失败支付单回查不可达 attemptRequestId={}: {}", attemptRequestId, e.getMessage());
            return null;
        }
    }

    /** 按尝试幂等键回查支付单（受理状态未知时的收敛依据；只调既有 listPayments）。 */
    private PaymentDTO findByRequestId(String attemptRequestId) {
        Result<List<PaymentDTO>> result = paymentFacade.listPayments();
        if (result == null || !result.isSuccess() || result.getData() == null) {
            return null;
        }
        for (PaymentDTO payment : result.getData()) {
            if (attemptRequestId.equals(payment.getRequestId())) {
                return payment;
            }
        }
        return null;
    }

    private static <T> T orNull(Result<T> result) {
        return result != null && result.isSuccess() ? result.getData() : null;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static SignDeductDTO toDTO(SignDeductDO row, boolean repeated) {
        SignDeductDTO dto = new SignDeductDTO();
        BeanUtils.copyProperties(row, dto);
        dto.setRepeated(repeated);
        return dto;
    }
}