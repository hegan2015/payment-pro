package com.qoder.payment.payment.service;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.cashier.CashierFacade;
import com.qoder.payment.facade.cashier.VerifiedPayRequest;
import com.qoder.payment.facade.payment.ChallengeContinueRequest;
import com.qoder.payment.facade.payment.PayResultDTO;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.TradeFacade;
import com.qoder.payment.payment.convert.PaymentConverter;
import com.qoder.payment.payment.dataobject.ChallengeDO;
import com.qoder.payment.payment.dataobject.PaymentDO;
import com.qoder.payment.payment.mapper.ChallengeMapper;
import com.qoder.payment.payment.mapper.PaymentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 风控 CHALLENGE 续付（R-4 分级处置 × C-3 收银台验证衔接，additive）。
 *
 * <p>现状保守口径不变：非 PASS 一律受理前拒绝（绝不落 PROCESSING 悬挂单）。
 * 本组件只把 <b>CHALLENGE</b> 一档开放「验证后续付」：
 * <ol>
 *   <li>原受理命中 CHALLENGE → 支付单 FAILED + payment_challenge 留痕（CHALLENGED，10 分钟窗口）；</li>
 *   <li>用户经收银台 C-3 {@code /api/cashier/verify} 验证取得一次性 verifyToken；</li>
 *   <li>续付入口以<b>新 requestId</b> 提交：CAS 签发单_use 续付授权（CONTINUING，60s，
 *       绑定 orderId+payerKey+amount+新 requestId）→ 经 {@code CashierFacade.payVerified}
 *       核销 token（绑定 order+amount+payer，失败即回退授权）→ 委托支付核心受理；</li>
 *   <li>受理侧风控仍全量执行：仅当分级仍为 CHALLENGE 且授权有效才核销放行；
 *       PASS 正常放行；REJECT/LIMIT/MANUAL_REVIEW 一律仍拒绝。</li>
 * </ol>
 *
 * <p><b>红线</b>：/openapi/** 冻结路径不接入本机制；BALANCE 同步语义不变（续付成功后
 * 仍走原同步 TCC）；幂等键仍 payment.request_id UNIQUE。开关
 * {@code payment.risk.challenge-continue.enabled}（默认 true，可一键回滚保守口径）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChallengeService {

    /** 挑战留痕有效期：10 分钟。 */
    private static final long CHALLENGE_TTL_MS = 600_000L;
    /** 续付授权窗口：60 秒（单_use，绑定续付 requestId）。 */
    private static final long GRANT_TTL_MS = 60_000L;

    private final ChallengeMapper challengeMapper;
    private final PaymentMapper paymentMapper;
    private final DynamicConfig dynamicConfig;

    @DubboReference
    private TradeFacade tradeFacade;

    @DubboReference
    private CashierFacade cashierFacade;

    public boolean enabled() {
        return dynamicConfig.getBool("payment.risk.challenge-continue.enabled", true);
    }

    public static String payerKey(String method, String accountId, String cardNo) {
        return "BALANCE".equals(method) ? accountId : "CARD:" + cardNo;
    }

    /** 原受理 CHALLENGE 拒绝留痕（幂等：同 requestId 重复忽略）。 */
    public void recordChallenge(String requestId, String orderId, String payerKey, long amount, String method) {
        try {
            ChallengeDO challenge = new ChallengeDO();
            challenge.setRequestId(requestId);
            challenge.setOrderId(orderId);
            challenge.setPayerKey(payerKey);
            challenge.setAmount(amount);
            challenge.setMethod(method);
            challenge.setStatus("CHALLENGED");
            challenge.setCreatedAt(System.currentTimeMillis());
            challenge.setExpireAt(System.currentTimeMillis() + CHALLENGE_TTL_MS);
            challengeMapper.insert(challenge);
        } catch (DuplicateKeyException e) {
            log.info("CHALLENGE 留痕幂等命中 requestId={}", requestId);
        }
    }

    /**
     * 受理侧核销续付授权：仅当分级仍为 CHALLENGE 时调用。
     * 授权必须绑定本 requestId + 同订单/付款人/金额且在窗口内；CAS 核销只一次。
     */
    public boolean tryConsumeGrant(String requestId, String orderId, String payerKey, long amount) {
        ChallengeDO grant = challengeMapper.selectGrant(requestId);
        if (grant == null || grant.getExpireAt() == null || grant.getExpireAt() <= System.currentTimeMillis()) {
            return false;
        }
        if (!grant.getOrderId().equals(orderId) || !grant.getPayerKey().equals(payerKey)
                || grant.getAmount() == null || grant.getAmount() != amount) {
            log.warn("续付授权绑定不匹配，拒绝放行 requestId={} orderId={} payerKey={}", requestId, orderId, payerKey);
            return false;
        }
        boolean consumed = challengeMapper.consume(requestId, System.currentTimeMillis()) == 1;
        if (consumed) {
            log.info("CHALLENGE 续付授权核销 requestId={} challengeRequestId={}", requestId, grant.getRequestId());
            MetricsSupport.count("payment_challenge_continue", "result", "consumed");
        }
        return consumed;
    }

    /** 续付入口：C-3 token 经 CashierFacade.payVerified 核销后委托支付核心（新 requestId）。 */
    public PayResultDTO continueChallenge(ChallengeContinueRequest request) {
        if (request == null || request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "requestId 必填（幂等键）");
        }
        if (!enabled()) {
            throw new BizException(ResultCode.BIZ_ERROR, "CHALLENGE 续付未启用");
        }
        String method = request.getMethod();
        if (!"BALANCE".equals(method) && !"CARD".equals(method)) {
            throw new BizException(ResultCode.PARAM_ERROR, "method 仅支持 BALANCE 或 CARD");
        }
        if ("BALANCE".equals(method) && (request.getAccountId() == null || request.getAccountId().isBlank())) {
            throw new BizException(ResultCode.PARAM_ERROR, "余额支付必须指定 accountId");
        }
        if ("CARD".equals(method) && (request.getCardNo() == null || request.getCardNo().isBlank())) {
            throw new BizException(ResultCode.PARAM_ERROR, "银行卡支付必须指定 cardNo");
        }
        if (request.getVerifyToken() == null || request.getVerifyToken().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "续付必须携带 C-3 verifyToken");
        }
        PaymentDO replay = paymentMapper.selectByRequestId(request.getRequestId());
        if (replay != null) {
            return result(replay, true);
        }
        OrderDTO order = tradeFacade.getOrder(request.getOrderId()).orThrow();
        if (!"CREATED".equals(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "订单状态不可支付: " + order.getStatus());
        }
        String payerKey = payerKey(method, request.getAccountId(), request.getCardNo());
        long now = System.currentTimeMillis();
        ChallengeDO challenge = challengeMapper.selectLatestChallenged(order.getId(), payerKey, now);
        if (challenge == null) {
            throw new BizException(ResultCode.CONFLICT, "无待验证的 CHALLENGE 挑战（请先受理触发风控分级）");
        }
        if (challengeMapper.grant(challenge.getRequestId(), request.getRequestId(), now + GRANT_TTL_MS) != 1) {
            throw new BizException(ResultCode.CONFLICT, "挑战已被其他续付占用，请重新验证");
        }
        VerifiedPayRequest verified = new VerifiedPayRequest();
        verified.setOrderId(request.getOrderId());
        verified.setMethod(method);
        verified.setAccountId(request.getAccountId());
        verified.setCardNo(request.getCardNo());
        verified.setRequestId(request.getRequestId());
        verified.setCallbackUrl(request.getCallbackUrl());
        verified.setVerifyToken(request.getVerifyToken());
        try {
            // token 核销（一次性 CAS，绑定 order+amount+payer）→ 委托支付核心受理（授权随受理核销）
            PayResultDTO result = cashierFacade.payVerified(verified).orThrow();
            MetricsSupport.count("payment_challenge_continue", "result", "success");
            return result;
        } catch (Exception e) {
            // 验证失败/受理失败：回退授权，允许重新验证续付（挑战留痕仍在窗口内）
            challengeMapper.revoke(challenge.getRequestId(), System.currentTimeMillis() + CHALLENGE_TTL_MS);
            MetricsSupport.count("payment_challenge_continue", "result", "revoked");
            throw e;
        }
    }

    private static PayResultDTO result(PaymentDO payment, boolean repeated) {
        PayResultDTO result = new PayResultDTO();
        PaymentDTO dto = PaymentConverter.toDTO(payment);
        result.setPayment(dto);
        result.setRepeated(repeated);
        return result;
    }
}