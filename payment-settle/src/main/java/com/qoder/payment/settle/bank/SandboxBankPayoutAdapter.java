package com.qoder.payment.settle.bank;

import com.qoder.payment.common.config.DynamicConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ST-4 沙箱银行出款适配器（local / docker 同一实现，零外部依赖，<b>绝不接真实银行</b>）。
 *
 * <p>确定性注入（便于断言，且重启后结论不变）：
 * <ul>
 *   <li>卡号以 {@code FAIL} 结尾 → FAILED（银行拒绝）；</li>
 *   <li>卡号以 {@code PENDING} 结尾 → 受理 PROCESSING，{@code settle.withdraw.sandbox-delay-ms}
 *       （默认 1500）后查询转 SUCCESS（模拟异步回执/回执丢失补单）；</li>
 *   <li>{@code settle.withdraw.sandbox-mode=async} → 所有出款先 PROCESSING，延迟后转 SUCCESS；</li>
 *   <li>其它 → 同步 SUCCESS。</li>
 * </ul>
 *
 * <p>无内存状态：结论只由 (卡号, createdAt, 配置) 决定 ⇒ 多实例/重启/重复补单结论一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxBankPayoutAdapter implements BankPayoutAdapter {

    public static final String KEY_MODE = "settle.withdraw.sandbox-mode";
    public static final String KEY_DELAY_MS = "settle.withdraw.sandbox-delay-ms";
    public static final long DEFAULT_DELAY_MS = 1500L;

    private final DynamicConfig dynamicConfig;

    @Override
    public String code() {
        return "SANDBOX_BANK";
    }

    @Override
    public PayoutResult submit(PayoutCommand command) {
        String orderNo = "SBX-" + command.bizNo();
        String card = command.bankCardNo() == null ? "" : command.bankCardNo().toUpperCase();
        if (card.endsWith("FAIL")) {
            log.info("ST-4 沙箱出款拒绝 bizNo={} card=***{}", command.bizNo(), tail(card));
            return PayoutResult.failed(orderNo, "沙箱银行拒绝出款（卡号 FAIL 注入）");
        }
        if (card.endsWith("PENDING") || "async".equalsIgnoreCase(dynamicConfig.get(KEY_MODE, "sync"))) {
            log.info("ST-4 沙箱出款受理在途 bizNo={} amount={}", command.bizNo(), command.amount());
            return PayoutResult.processing(orderNo);
        }
        log.info("ST-4 沙箱出款同步到账 bizNo={} amount={}", command.bizNo(), command.amount());
        return PayoutResult.success(orderNo);
    }

    @Override
    public PayoutResult query(PayoutCommand command) {
        String orderNo = "SBX-" + command.bizNo();
        String card = command.bankCardNo() == null ? "" : command.bankCardNo().toUpperCase();
        if (card.endsWith("FAIL")) {
            return PayoutResult.failed(orderNo, "沙箱银行拒绝出款（卡号 FAIL 注入）");
        }
        long delay = Math.max(0, dynamicConfig.getLong(KEY_DELAY_MS, DEFAULT_DELAY_MS));
        if (System.currentTimeMillis() - command.createdAt() >= delay) {
            return PayoutResult.success(orderNo);
        }
        return PayoutResult.processing(orderNo);
    }

    private static String tail(String card) {
        return card.length() <= 4 ? card : card.substring(card.length() - 4);
    }
}