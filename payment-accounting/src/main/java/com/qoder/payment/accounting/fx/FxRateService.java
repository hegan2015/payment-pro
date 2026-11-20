package com.qoder.payment.accounting.fx;

import com.qoder.payment.accounting.dataobject.FxRateDO;
import com.qoder.payment.accounting.daycut.AccountingDayClock;
import com.qoder.payment.accounting.mapper.CurrencyRegistryMapper;
import com.qoder.payment.accounting.mapper.FxRateMapper;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.accounting.FxRateDTO;
import com.qoder.payment.facade.accounting.FxRateSetRequest;
import com.qoder.tcc.IdempotentTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A-4 汇率服务：版本化汇率表（买入/卖出/中间价）+ 生效时间 + 来源沙箱可配 + 币种账套目录。
 *
 * <p><b>只追加</b>：每次发布产生新版本行，历史版本永不 UPDATE/DELETE；
 * 换汇入账时把当时的版本快照落 {@code fx_exchange} 与分录 {@code exchange_rate/base_amount}，
 * <b>后续汇率变动不影响已入账</b>（红线）。
 *
 * <p><b>幂等</b>：{@code (FX_RATE, requestId|派生键)} —— 同参数重复发布返回既有版本，不产生新版本。
 * <p><b>long + scale</b>：汇率与折算全程整数分 + BigDecimal 精确十进制，杜绝浮点。
 * <p><b>不分片</b>：fx_rate / accounting_currency 均为全局元数据表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@DependsOn("accountingSchemaUpgrade")
public class FxRateService implements InitializingBean {

    public static final String BASE_CURRENCY = "CNY";

    private final FxRateMapper fxRateMapper;
    private final CurrencyRegistryMapper currencyRegistryMapper;
    private final SandboxFxRateSource sandboxSource;
    private final DynamicConfig dynamicConfig;
    private final IdempotentTemplate idempotentTemplate;
    private final AccountingDayClock dayClock;

    @Override
    public void afterPropertiesSet() {
        seed();
    }

    /** 幂等播种：币种账套目录 + 沙箱初始汇率版本（存在即跳过，绝不覆盖既有行）。 */
    public synchronized void seed() {
        try {
            for (String ccy : configuredCurrencies()) {
                registerCurrency(ccy);
            }
            registerCurrency(BASE_CURRENCY);
            if (!dynamicConfig.getBool(SandboxFxRateSource.KEY_SANDBOX_ENABLED, true)) {
                log.info("A-4 沙箱汇率播种关闭（accounting.fx.sandbox.enabled=false）");
                return;
            }
            int seeded = 0;
            for (String pair : dynamicConfig.get(SandboxFxRateSource.KEY_PAIRS, "CNY:USD,USD:CNY").split(",")) {
                String[] parts = pair.trim().split(":");
                if (parts.length != 2) {
                    continue;
                }
                String from = FxMath.normalize(parts[0]);
                String to = FxMath.normalize(parts[1]);
                if (from.equals(to) || fxRateMapper.maxVersion(from, to) != null) {
                    continue;
                }
                long[] quote = sandboxSource.quote(from, to);
                if (quote == null) {
                    continue;
                }
                FxRateSetRequest req = new FxRateSetRequest();
                req.setFromCurrency(from);
                req.setToCurrency(to);
                req.setBuyRate(quote[0]);
                req.setSellRate(quote[1]);
                req.setMidRate(quote[2]);
                req.setRateScale((int) quote[3]);
                req.setVersion(1L);
                req.setEffectiveAt(System.currentTimeMillis());
                req.setSource(sandboxSource.name());
                req.setOperator("system-seed");
                req.setRequestId("SEED_" + from + "_" + to + "_V1");
                publish(req);
                seeded++;
            }
            log.info("A-4 汇率就绪 currencies={} newRateVersions={} provider={} rates={}",
                    currencies(), seeded, sandboxSource.name(), fxRateMapper.count());
        } catch (Exception e) {
            // 播种失败不阻断启动：汇率可经 setFxRate 显式发布；已落库版本仍可用（资金可用性优先）
            log.warn("A-4 汇率播种失败（不阻断启动，可经 setFxRate 补发布）: {}", e.toString());
        }
    }

    /** 币种账套目录（CNY 恒首位）。 */
    public List<String> currencies() {
        List<String> rows = currencyRegistryMapper.selectAll();
        Set<String> out = new LinkedHashSet<>();
        out.add(BASE_CURRENCY);
        if (rows != null) {
            out.addAll(rows);
        }
        return new ArrayList<>(out);
    }

    /** 登记币种账套（幂等，只 INSERT）。 */
    public void registerCurrency(String currency) {
        String ccy = FxMath.normalize(currency);
        if (currencyRegistryMapper.countByCurrency(ccy) > 0) {
            return;
        }
        try {
            currencyRegistryMapper.insert(ccy, BASE_CURRENCY.equals(ccy), System.currentTimeMillis());
        } catch (DuplicateKeyException ignored) {
            // 并发启动竞争：对手已插入
        }
    }

    /** 当前生效汇率（或指定版本）；无则 NOT_FOUND —— 绝不臆造汇率动账。 */
    public FxRateDO require(String fromCurrency, String toCurrency, Long version) {
        String from = FxMath.normalize(fromCurrency);
        String to = FxMath.normalize(toCurrency);
        FxRateDO row = version == null
                ? fxRateMapper.selectEffective(from, to, System.currentTimeMillis())
                : fxRateMapper.selectByVersion(from, to, version);
        if (row == null) {
            throw new BizException(ResultCode.NOT_FOUND,
                    "汇率不存在或未生效: " + from + "→" + to + (version == null ? "" : " v" + version));
        }
        return row;
    }

    public FxRateDTO getFxRate(String fromCurrency, String toCurrency, Long version) {
        return toDTO(require(fromCurrency, toCurrency, version));
    }

    public List<FxRateDTO> listFxRates(String fromCurrency, String toCurrency, int limit) {
        int safe = limit <= 0 ? 20 : Math.min(limit, 200);
        String from = fromCurrency == null || fromCurrency.isBlank() ? null : FxMath.normalize(fromCurrency);
        String to = toCurrency == null || toCurrency.isBlank() ? null : FxMath.normalize(toCurrency);
        return fxRateMapper.selectRecent(from, to, safe).stream().map(FxRateService::toDTO).toList();
    }

    /**
     * 本位币折算率（currency → CNY，中间价）：先取直接币对，缺失则取反向币对求逆。
     * 两者皆无 → null（trial 该币种 base* 字段置 null 且不计入本位币汇总，<b>绝不按 1:1 混算</b>）。
     *
     * @return {@code {rate, scale}} 或 null
     */
    public long[] baseRate(String currency) {
        String ccy = FxMath.normalize(currency);
        if (BASE_CURRENCY.equals(ccy)) {
            return new long[]{FxMath.RATE_ONE, FxMath.DEFAULT_SCALE};
        }
        long now = System.currentTimeMillis();
        FxRateDO direct = fxRateMapper.selectEffective(ccy, BASE_CURRENCY, now);
        if (direct != null) {
            return new long[]{direct.getMidRate(), direct.getRateScale()};
        }
        FxRateDO inverse = fxRateMapper.selectEffective(BASE_CURRENCY, ccy, now);
        if (inverse != null) {
            return new long[]{FxMath.invert(inverse.getMidRate(), inverse.getRateScale()), inverse.getRateScale()};
        }
        return null;
    }

    /** 发布汇率版本（幂等）。 */
    public FxRateDTO setFxRate(FxRateSetRequest request) {
        if (request == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "请求体必填");
        }
        String from = FxMath.normalize(request.getFromCurrency());
        String to = FxMath.normalize(request.getToCurrency());
        if (from.equals(to)) {
            throw new BizException(ResultCode.PARAM_ERROR, "同币种无需汇率: " + from);
        }
        int scale = request.getRateScale() == null || request.getRateScale() <= 0
                ? FxMath.DEFAULT_SCALE : request.getRateScale();
        long buy = requirePositive(request.getBuyRate(), "buyRate");
        long sell = requirePositive(request.getSellRate(), "sellRate");
        long mid = requirePositive(request.getMidRate(), "midRate");
        FxMath.requireSpread(buy, mid, sell);
        long effectiveAt = request.getEffectiveAt() == null ? System.currentTimeMillis() : request.getEffectiveAt();
        String source = request.getSource() == null || request.getSource().isBlank()
                ? sandboxSource.name() : request.getSource();
        String key = request.getRequestId() == null || request.getRequestId().isBlank()
                ? deriveKey(from, to, request.getVersion(), effectiveAt, buy, mid, sell, scale)
                : request.getRequestId();
        if (key.length() > 64) {
            key = from + "_" + to + "_" + Integer.toHexString(key.hashCode());
        }
        final String idempotentKey = key;
        return idempotentTemplate.execute("FX_RATE", idempotentKey, FxRateDTO.class, () -> {
            long version = request.getVersion() != null ? request.getVersion()
                    : (fxRateMapper.maxVersion(from, to) == null ? 1L : fxRateMapper.maxVersion(from, to) + 1);
            FxRateDO existing = fxRateMapper.selectByVersion(from, to, version);
            if (existing != null) {
                // 同版本已存在：参数一致视为幂等返回；不一致拒绝（历史版本不可篡改）
                assertSameQuote(existing, buy, sell, mid, scale, effectiveAt);
                return toDTO(existing);
            }
            FxRateDO row = new FxRateDO();
            row.setId("FX" + fxRateMapper.nextId());
            row.setFromCurrency(from);
            row.setToCurrency(to);
            row.setBuyRate(buy);
            row.setSellRate(sell);
            row.setMidRate(mid);
            row.setRateScale(scale);
            row.setVersion(version);
            row.setEffectiveAt(effectiveAt);
            row.setBizDate(dayClock.currentDay());
            row.setSource(source);
            row.setOperator(request.getOperator());
            row.setCreatedAt(System.currentTimeMillis());
            try {
                fxRateMapper.insert(row);
            } catch (DuplicateKeyException e) {
                FxRateDO winner = fxRateMapper.selectByVersion(from, to, version);
                if (winner == null) {
                    throw e;
                }
                assertSameQuote(winner, buy, sell, mid, scale, effectiveAt);
                return toDTO(winner);
            }
            registerCurrency(from);
            registerCurrency(to);
            log.info("A-4 汇率发布 {}→{} v{} buy={} mid={} sell={} scale={} effectiveAt={} source={}",
                    from, to, version, buy, mid, sell, scale, effectiveAt, source);
            return toDTO(row);
        });
    }

    /** 内部播种用（不走幂等表，直接落版本；仅供 seed 调用）。 */
    private FxRateDTO publish(FxRateSetRequest request) {
        return setFxRate(request);
    }

    private static void assertSameQuote(FxRateDO existing, long buy, long sell, long mid, int scale, long effectiveAt) {
        if (existing.getBuyRate() != buy || existing.getSellRate() != sell || existing.getMidRate() != mid
                || existing.getRateScale() != scale || existing.getEffectiveAt() != effectiveAt) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "汇率版本不可篡改: " + existing.getFromCurrency() + "→" + existing.getToCurrency()
                            + " v" + existing.getVersion() + "（请以新版本号发布）");
        }
    }

    private static String deriveKey(String from, String to, Long version, long effectiveAt,
                                    long buy, long mid, long sell, int scale) {
        if (version != null) {
            return from + "_" + to + "_V" + version;
        }
        return from + "_" + to + "_" + effectiveAt + "_" + buy + "_" + mid + "_" + sell + "_" + scale;
    }

    private static long requirePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, name + " 必填且为正整数（long+scale）");
        }
        return value;
    }

    private List<String> configuredCurrencies() {
        List<String> out = new ArrayList<>();
        for (String part : dynamicConfig.get(SandboxFxRateSource.KEY_CURRENCIES, "CNY,USD").split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim().toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    public static FxRateDTO toDTO(FxRateDO row) {
        FxRateDTO dto = new FxRateDTO();
        dto.setId(row.getId());
        dto.setFromCurrency(row.getFromCurrency());
        dto.setToCurrency(row.getToCurrency());
        dto.setBuyRate(row.getBuyRate());
        dto.setSellRate(row.getSellRate());
        dto.setMidRate(row.getMidRate());
        dto.setRateScale(row.getRateScale());
        dto.setVersion(row.getVersion());
        dto.setEffectiveAt(row.getEffectiveAt());
        dto.setBizDate(row.getBizDate());
        dto.setSource(row.getSource());
        dto.setOperator(row.getOperator());
        dto.setCreatedAt(row.getCreatedAt());
        return dto;
    }
}