package com.qoder.payment.settle.fee;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.payment.common.config.ConfigKeys;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.settle.FeeCalcDTO;
import com.qoder.payment.facade.settle.FeeCalcRequest;
import com.qoder.payment.facade.settle.FeeRuleDTO;
import com.qoder.payment.facade.settle.FeeTierDTO;
import com.qoder.payment.facade.settle.SettleCycleDTO;
import com.qoder.payment.settle.mapper.FeeRuleMapper;
import com.qoder.payment.settle.mapper.SettleCycleMapper;
import com.qoder.payment.settle.service.SettleSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ST-2 费率配置服务：分层规则（商户 / 交易类型 / 支付方式）+ Apollo {@code settle-fee} 热更 + 缺省回退。
 *
 * <p><b>命中顺序</b>（先具体后宽泛，DB 权威）：
 * <ol>
 *   <li>fee_rule (merchant, productType, method)</li>
 *   <li>fee_rule (merchant, productType, *) → (merchant, *, *) → (*, productType, method) → (*, *, *)</li>
 *   <li>settle_cycle.fee_bps（商户级费率层）</li>
 *   <li>DynamicConfig {@code settle.fee.bps.<merchantId>} → {@code settle.fee.default-bps}（settle-fee 热更）</li>
 *   <li>DynamicConfig {@code payment.fee-bps}，代码默认 {@link ConfigKeys#DEFAULT_FEE_BPS}=60（<b>现值口径</b>）</li>
 * </ol>
 *
 * <p><b>快照红线</b>：本服务只服务「新单计费预览 / 结算侧费率展示」。已受理单的手续费以
 * payment.fee 落库快照为准（{@code settle_record.fee} 直接来自该快照），费率热更<b>绝不</b>
 * 重算在途单与历史结算单/批次/账单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeeRuleService {

    public static final String WILDCARD = "*";
    public static final String KEY_DEFAULT_BPS = "settle.fee.default-bps";
    public static final String KEY_MERCHANT_BPS_PREFIX = "settle.fee.bps.";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FeeRuleMapper feeRuleMapper;
    private final SettleCycleMapper settleCycleMapper;
    private final FeeCalculator feeCalculator;
    private final DynamicConfig dynamicConfig;
    private final SettleSupport support;

    /** 现值缺省费率（证明口径未变：无规则/无配置时必须等于 60）。 */
    public int defaultBps() {
        return dynamicConfig.getInt(ConfigKeys.PAYMENT_FEE_BPS, ConfigKeys.DEFAULT_FEE_BPS);
    }

    public List<FeeRuleDTO> list(String merchantId) {
        List<FeeRuleDTO> rules = merchantId == null || merchantId.isBlank()
                ? feeRuleMapper.selectActive() : feeRuleMapper.selectByMerchant(merchantId);
        rules.forEach(this::hydrate);
        return rules;
    }

    public FeeRuleDTO save(FeeRuleDTO rule) {
        if (rule == null || blank(rule.getMerchantId())) {
            throw new BizException(ResultCode.PARAM_ERROR, "merchantId 必填（通配用 *）");
        }
        String productType = blank(rule.getProductType()) ? WILDCARD : rule.getProductType();
        String method = blank(rule.getMethod()) ? WILDCARD : rule.getMethod();
        String feeType = blank(rule.getFeeType()) ? "FIXED" : rule.getFeeType();
        if (!List.of("FIXED", "TIERED", "SHARE").contains(feeType)) {
            throw new BizException(ResultCode.PARAM_ERROR, "feeType 仅支持 FIXED/TIERED/SHARE");
        }
        if ("TIERED".equals(feeType)) {
            if (rule.getTiers() == null || rule.getTiers().isEmpty()) {
                throw new BizException(ResultCode.PARAM_ERROR, "TIERED 必须提供 tiers");
            }
            for (FeeTierDTO tier : rule.getTiers()) {
                if (tier.getBps() == null || tier.getBps() < 0 || tier.getBps() > 10000) {
                    throw new BizException(ResultCode.PARAM_ERROR, "tier.bps 需在 0~10000");
                }
            }
        } else if (rule.getFeeBps() == null || rule.getFeeBps() < 0 || rule.getFeeBps() > 10000) {
            throw new BizException(ResultCode.PARAM_ERROR, "feeBps 需在 0~10000");
        }
        rule.setProductType(productType);
        rule.setMethod(method);
        rule.setFeeType(feeType);
        rule.setStatus(blank(rule.getStatus()) ? "ACTIVE" : rule.getStatus());
        rule.setTiersJson(writeTiers(rule.getTiers()));
        long now = System.currentTimeMillis();
        FeeRuleDTO existing = feeRuleMapper.selectByKey(rule.getMerchantId(), productType, method);
        String oldJson = existing == null ? null : writeRule(existing);
        if (existing == null) {
            rule.setId(support.newId("F"));
            rule.setVersion(1);
            rule.setCreatedAt(now);
            rule.setUpdatedAt(now);
            feeRuleMapper.insert(rule);
        } else {
            rule.setId(existing.getId());
            rule.setVersion(existing.getVersion() == null ? 1 : existing.getVersion() + 1);
            rule.setCreatedAt(existing.getCreatedAt());
            rule.setUpdatedAt(now);
            feeRuleMapper.update(rule);
        }
        feeRuleMapper.insertAudit(support.newId("A"), rule.getId(), rule.getMerchantId(), productType, method,
                oldJson, writeRule(rule), "console", now);
        log.info("ST-2 费率规则保存 merchant={} product={} method={} type={} bps={} version={}",
                rule.getMerchantId(), productType, method, feeType, rule.getFeeBps(), rule.getVersion());
        hydrate(rule);
        return rule;
    }

    public FeeCalcDTO calc(FeeCalcRequest request) {
        if (request == null || request.getAmount() == null || request.getAmount() <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "amount 必须为正整数（分）");
        }
        String merchantId = blank(request.getMerchantId()) ? WILDCARD : request.getMerchantId();
        String productType = blank(request.getProductType()) ? WILDCARD : request.getProductType();
        String method = blank(request.getMethod()) ? WILDCARD : request.getMethod();
        long amount = request.getAmount();
        Resolved resolved = resolve(merchantId, productType, method);
        int bps = feeCalculator.effectiveBps(amount, resolved.feeType(), resolved.feeBps(), resolved.tiers());
        long fee = feeCalculator.fee(amount, resolved.feeType(), resolved.feeBps(), resolved.tiers());
        FeeCalcDTO dto = new FeeCalcDTO();
        dto.setMerchantId(merchantId);
        dto.setProductType(productType);
        dto.setMethod(method);
        dto.setAmount(amount);
        dto.setFeeBps(bps);
        dto.setFee(fee);
        dto.setNet(amount - fee);
        dto.setFeeType(resolved.feeType());
        dto.setRuleId(resolved.ruleId());
        dto.setRuleVersion(resolved.version());
        dto.setSource(resolved.source());
        return dto;
    }

    /** 分层解析（只读，不落库）。 */
    public Resolved resolve(String merchantId, String productType, String method) {
        String[][] candidates = {
                {merchantId, productType, method},
                {merchantId, productType, WILDCARD},
                {merchantId, WILDCARD, WILDCARD},
                {WILDCARD, productType, method},
                {WILDCARD, WILDCARD, WILDCARD}
        };
        for (String[] key : candidates) {
            FeeRuleDTO rule = feeRuleMapper.selectByKey(key[0], key[1], key[2]);
            if (rule != null && "ACTIVE".equals(rule.getStatus())) {
                hydrate(rule);
                return new Resolved(rule.getFeeType(), rule.getFeeBps(), rule.getTiers(),
                        rule.getId(), rule.getVersion(), "RULE");
            }
        }
        if (!WILDCARD.equals(merchantId)) {
            SettleCycleDTO cycle = settleCycleMapper.select(merchantId);
            if (cycle != null && cycle.getFeeBps() != null) {
                return new Resolved("FIXED", cycle.getFeeBps(), null, null, null, "CYCLE");
            }
            String perMerchant = dynamicConfig.get(KEY_MERCHANT_BPS_PREFIX + merchantId, null);
            Integer parsed = parseInt(perMerchant);
            if (parsed != null) {
                return new Resolved("FIXED", parsed, null, null, null, "CONFIG");
            }
        }
        Integer global = parseInt(dynamicConfig.get(KEY_DEFAULT_BPS, null));
        if (global != null) {
            return new Resolved("FIXED", global, null, null, null, "CONFIG");
        }
        return new Resolved("FIXED", defaultBps(), null, null, null, "DEFAULT");
    }

    /** 解析结果（feeType/bps/tiers/规则溯源）。 */
    public record Resolved(String feeType, Integer feeBps, List<FeeTierDTO> tiers,
                           String ruleId, Integer version, String source) {
    }

    private void hydrate(FeeRuleDTO rule) {
        if (rule == null) {
            return;
        }
        rule.setTiers(readTiers(rule.getTiersJson()));
    }

    private static List<FeeTierDTO> readTiers(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, new TypeReference<List<FeeTierDTO>>() {
            });
        } catch (Exception e) {
            log.warn("ST-2 tiers 解析失败，回退固定费率: {}", e.getMessage());
            return null;
        }
    }

    private static String writeTiers(List<FeeTierDTO> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(tiers);
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_ERROR, "tiers 序列化失败: " + e.getMessage());
        }
    }

    private static String writeRule(FeeRuleDTO rule) {
        try {
            List<Object> flat = new ArrayList<>();
            flat.add(rule.getFeeType());
            flat.add(rule.getFeeBps());
            flat.add(rule.getTiersJson());
            flat.add(rule.getShareRatio());
            flat.add(rule.getVersion());
            return JSON.writeValueAsString(flat);
        } catch (Exception e) {
            return String.valueOf(rule.getFeeBps());
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 配置值解析：缺失/非法/越界一律返回 null（继续回退下一层，绝不因坏配置阻断计费）。 */
    private static Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= 0 && parsed <= 10000 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}