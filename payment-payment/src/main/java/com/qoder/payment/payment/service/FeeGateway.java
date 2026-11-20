package com.qoder.payment.payment.service;

import com.qoder.payment.common.config.ConfigKeys;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.payment.facade.settle.FeeCalcDTO;
import com.qoder.payment.facade.settle.FeeCalcRequest;
import com.qoder.payment.facade.settle.SettleFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

/**
 * P1-I 计费接线：payment 受理计费改接 settle 域 {@code SettleFacade.calcFee}
 * （三层费率 RULE/CYCLE/CONFIG/DEFAULT，ST-2 FeeCalculator 同一公式 MoneyUtil.fee）。
 *
 * <p><b>口径红线（§0.7）</b>：缺省仍 60bps（calcFee DEFAULT 层与本地 fallback 同读
 * {@code payment.fee-bps}，默认 {@link ConfigKeys#DEFAULT_FEE_BPS}=60）；fee 随支付单
 * 落库即<b>受理快照</b>，二阶段/结算/退款仍读 payment.fee，配置热更不影响在途单。
 *
 * <p><b>降级红线</b>：settle 域不可达/返回非法 → 回退本地 DynamicConfig 计费（与改造前
 * 逐字节同公式），绝不因计费依赖阻断受理；降级留痕 + 指标。资金单（TOPUP/WITHDRAW）
 * 不经过本组件（fee=0 路径不变）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeeGateway {

    private final DynamicConfig dynamicConfig;

    @DubboReference
    private SettleFacade settleFacade;

    public long feeFor(String merchantId, String productType, String method, long amount) {
        try {
            FeeCalcRequest request = new FeeCalcRequest();
            request.setMerchantId(merchantId);
            request.setProductType(productType);
            request.setMethod(method);
            request.setAmount(amount);
            FeeCalcDTO calc = settleFacade.calcFee(request).orThrow();
            if (calc != null && calc.getFee() != null && calc.getFee() >= 0 && calc.getFee() <= amount) {
                return calc.getFee();
            }
            log.warn("计费返回非法，降级本地口径 merchantId={} amount={} calc={}", merchantId, amount, calc);
        } catch (Exception e) {
            log.warn("settle 计费不可达，降级本地口径 merchantId={} amount={}: {}", merchantId, amount, e.getMessage());
        }
        MetricsSupport.count("payment_fee_fallback");
        return localFee(amount);
    }

    /** 本地口径（与改造前 pay() 内联计费逐字节一致）：payment.fee-bps，默认 60，非法值回默认。 */
    public long localFee(long amount) {
        int feeBps = dynamicConfig.getInt(ConfigKeys.PAYMENT_FEE_BPS, ConfigKeys.DEFAULT_FEE_BPS);
        if (feeBps < 0 || feeBps > 10000) {
            log.warn("费率配置非法 payment.fee-bps={}，用默认值 {}", feeBps, ConfigKeys.DEFAULT_FEE_BPS);
            feeBps = ConfigKeys.DEFAULT_FEE_BPS;
        }
        return MoneyUtil.fee(amount, feeBps);
    }
}