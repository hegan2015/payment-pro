package com.qoder.payment.settle.fee;

import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.payment.facade.settle.FeeTierDTO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ST-2 计费器（SPI 语义：FIXED 固定基点 / TIERED 阶梯 / SHARE 分成比例）。
 *
 * <p><b>口径红线</b>：金额一律 long「分」，{@code fee = (amount*bps + 5000)/10000}
 * 直接复用 {@link MoneyUtil#fee}（四舍五入语义与改造前逐字节一致，ride 手续费对账不变）。
 * 阶梯只是「按金额区间选 bps」，不改计费公式。
 *
 * <p>本类只做纯计算，不落库、不改任何在途单；已受理单的手续费以 payment.fee 落库快照为准。
 */
@Component
public class FeeCalculator {

    /** 命中区间的 bps；TIERED 未命中任何区间时回退 fixedBps。 */
    public int effectiveBps(long amount, String feeType, Integer fixedBps, List<FeeTierDTO> tiers) {
        if ("TIERED".equals(feeType) && tiers != null) {
            for (FeeTierDTO tier : tiers) {
                long min = tier.getMin() == null ? 0L : tier.getMin();
                Long max = tier.getMax();
                if (amount >= min && (max == null || amount < max) && tier.getBps() != null) {
                    return tier.getBps();
                }
            }
        }
        return fixedBps == null ? 0 : fixedBps;
    }

    public long fee(long amount, String feeType, Integer fixedBps, List<FeeTierDTO> tiers) {
        return MoneyUtil.fee(amount, effectiveBps(amount, feeType, fixedBps, tiers));
    }
}