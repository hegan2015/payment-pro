package com.qoder.payment.risk.service;

import com.qoder.payment.facade.risk.RiskCheckRequest;
import com.qoder.payment.facade.risk.RiskResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 风控规则（简化版规则引擎）：
 * 1. 单笔限额 10 万元（10_000_000 分）；
 * 2. 同一付款方 10 秒内不超过 5 笔（滑动窗口，本地内存 / docker Redis 可切换）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskService {

    private static final long SINGLE_LIMIT = 10_000_000L;
    private static final long WINDOW_MILLIS = 10_000L;
    private static final int WINDOW_MAX = 5;

    private final RateLimiter rateLimiter;

    public RiskResultDTO check(RiskCheckRequest request) {
        if (request.getAmount() != null && request.getAmount() > SINGLE_LIMIT) {
            log.warn("风控拒绝 amount={} reason=超出单笔限额 100000 元", request.getAmount());
            return reject("超出单笔限额 100000 元");
        }
        String payer = request.getPayerId() == null ? "anonymous" : request.getPayerId();
        if (!rateLimiter.tryAcquire(payer, System.currentTimeMillis(), WINDOW_MILLIS, WINDOW_MAX)) {
            log.warn("风控拒绝 payerId={} reason=交易过于频繁", payer);
            return reject("交易过于频繁，请稍后再试");
        }
        RiskResultDTO result = new RiskResultDTO();
        result.setPass(true);
        log.info("风控通过 payerId={} amount={} method={}", payer, request.getAmount(), request.getMethod());
        return result;
    }

    private static RiskResultDTO reject(String reason) {
        RiskResultDTO result = new RiskResultDTO();
        result.setPass(false);
        result.setReason(reason);
        return result;
    }
}