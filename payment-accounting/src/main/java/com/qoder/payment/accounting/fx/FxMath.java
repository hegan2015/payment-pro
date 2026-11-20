package com.qoder.payment.accounting.fx;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A-4/AC-6 汇率与折算工具：<b>全程 long「分」+ 整数标度</b>，中间运算用 {@link BigDecimal}
 * 精确十进制（绝不使用 float/double，杜绝浮点误差，红线 §0.2-9）。
 *
 * <p>汇率表示：{@code rate / scale}，如 rate=71234 scale=10000 → 7.1234。
 * 折算：{@code target = source × rate / scale}，四舍五入到最小货币单位（HALF_UP）。
 */
public final class FxMath {

    /** 缺省标度（1e4，与计划示例 rate=71234 scale=10000 一致）。 */
    public static final int DEFAULT_SCALE = 10000;
    /** 1:1 折算率（本位币 CNY → CNY）。 */
    public static final long RATE_ONE = DEFAULT_SCALE;

    private FxMath() {
    }

    /** 折算：source × rate / scale，HALF_UP 到整数分；溢出/非法参数拒绝。 */
    public static long convert(long source, long rate, int scale) {
        if (scale <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "rateScale 必须为正整数");
        }
        if (rate <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "汇率必须为正整数（long+scale）");
        }
        try {
            return BigDecimal.valueOf(source)
                    .multiply(BigDecimal.valueOf(rate))
                    .divide(BigDecimal.valueOf(scale), 0, RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (ArithmeticException e) {
            throw new BizException(ResultCode.PARAM_ERROR, "折算额溢出 long（分）: " + source + " × " + rate + "/" + scale);
        }
    }

    /**
     * 反向汇率（from→to 的 rate 求 to→from）：{@code rate' = scale² / rate}，同标度 HALF_UP。
     * 用于只配置了单向中间价时的本位币折算（精确十进制，无浮点）。
     */
    public static long invert(long rate, int scale) {
        if (rate <= 0 || scale <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "汇率/标度必须为正整数");
        }
        return BigDecimal.valueOf(scale).multiply(BigDecimal.valueOf(scale))
                .divide(BigDecimal.valueOf(rate), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** 币种归一：null/空白 → CNY；其余 trim + 大写，必须是 ISO 4217 三字母。 */
    public static String normalize(String currency) {
        if (currency == null || currency.isBlank()) {
            return "CNY";
        }
        String ccy = currency.trim().toUpperCase(java.util.Locale.ROOT);
        if (!ccy.matches("[A-Z]{3}")) {
            throw new BizException(ResultCode.PARAM_ERROR, "currency 需为 ISO 4217 三字母代码: " + currency);
        }
        return ccy;
    }

    /** 点差口径校验：买入价 ≤ 中间价 ≤ 卖出价（银行视角，平台侧）。 */
    public static void requireSpread(long buy, long mid, long sell) {
        if (buy <= 0 || mid <= 0 || sell <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "买入/卖出/中间价必须均为正整数（long+scale）");
        }
        if (buy > mid || mid > sell) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "点差口径非法：需 buyRate ≤ midRate ≤ sellRate，实际 " + buy + "/" + mid + "/" + sell);
        }
    }
}