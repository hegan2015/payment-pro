package com.qoder.payment.recon.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * S-4 调度兜底：recon.dispute.autofix=true 时周期执行 OPEN 差错单自动修复；
 * 默认 false（影子模式）不调度动账——差错单已在对账/死信接入时生成并带建议策略。
 * 人工触发走 POST /api/disputes/autofix/run（execute=true 显式授权）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DisputeAutoFixRunner {

    private final DisputeAutoFixer disputeAutoFixer;

    @Value("${recon.dispute.autofix:false}")
    private boolean autofixEnabled;

    @Scheduled(fixedDelay = 60000, initialDelay = 45000)
    public void run() {
        if (!autofixEnabled) {
            return;
        }
        try {
            disputeAutoFixer.runAll(true, "scheduler");
        } catch (Exception e) {
            log.error("[告警] 差错自动修复调度异常", e);
        }
    }
}