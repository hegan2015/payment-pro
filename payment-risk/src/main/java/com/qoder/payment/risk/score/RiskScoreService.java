package com.qoder.payment.risk.score;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.facade.risk.ListEntryDTO;
import com.qoder.payment.facade.risk.RiskFactorDTO;
import com.qoder.payment.risk.config.RiskConfigManager;
import com.qoder.payment.risk.config.RiskKeys;
import com.qoder.payment.risk.engine.RiskContext;
import com.qoder.payment.risk.quota.QuotaService;
import com.qoder.payment.risk.service.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * R-4 多因子加权评分（频率 / 金额偏离 / 名单 / 设备 / IP / 时段 + 规则命中分）。
 *
 * <p><b>默认宽松红线（§0.7）</b>：频率阈值 5（与限频上限同量级）、金额偏离需 ≥5 笔历史且
 * 历史累计开关默认关闭、风险设备/IP/时段名单默认空 → <b>正常流量（含 ride 镜像资金流）score=0</b>。
 * <p>每个因子都产出 {@link RiskFactorDTO}（权重/观测值/得分/说明），命中与否全留痕可解释。
 */
@Slf4j
@Service
public class RiskScoreService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DynamicConfig config;
    private final RiskConfigManager configs;
    private final RateLimiter rateLimiter;
    private final QuotaService quotaService;

    public RiskScoreService(DynamicConfig config, RiskConfigManager configs, RateLimiter rateLimiter,
                            QuotaService quotaService) {
        this.config = config;
        this.configs = configs;
        this.rateLimiter = rateLimiter;
        this.quotaService = quotaService;
    }

    /** 评分结果：总分 + 因子明细。 */
    public record Score(int total, List<RiskFactorDTO> factors) {
    }

    public Map<String, Integer> weights() {
        Map<String, Integer> w = new LinkedHashMap<>();
        w.put("FREQ", RiskKeys.WEIGHT_FREQ);
        w.put("AMOUNT_DEV", RiskKeys.WEIGHT_AMOUNT_DEV);
        w.put("LIST", RiskKeys.WEIGHT_LIST);
        w.put("DEVICE", RiskKeys.WEIGHT_DEVICE);
        w.put("IP", RiskKeys.WEIGHT_IP);
        w.put("TIME", RiskKeys.WEIGHT_TIME);
        String raw = config.get(RiskKeys.SCORE_WEIGHTS, null);
        if (raw != null && !raw.isBlank()) {
            try {
                Map<?, ?> m = MAPPER.readValue(raw, Map.class);
                m.forEach((k, v) -> {
                    if (v instanceof Number n) {
                        w.put(String.valueOf(k).toUpperCase(), n.intValue());
                    }
                });
            } catch (Exception e) {
                log.warn("评分权重配置解析失败，用默认权重: {}", e.getMessage());
            }
        }
        return w;
    }

    public Set<String> riskDevices() {
        return csv(config.get(RiskKeys.SCORE_RISK_DEVICES, ""));
    }

    public Set<String> riskIps() {
        return csv(config.get(RiskKeys.SCORE_RISK_IPS, ""));
    }

    public Set<String> riskHours() {
        return csv(config.get(RiskKeys.SCORE_RISK_HOURS, ""));
    }

    private static Set<String> csv(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    public boolean amountHistoryEnabled() {
        return config.getBool(RiskKeys.SCORE_AMOUNT_HISTORY, RiskKeys.DEFAULT_SCORE_AMOUNT_HISTORY);
    }

    /** 计算评分（不含规则分，规则分由调用方累加为 RULE 因子）。 */
    public Score score(RiskContext ctx, ListEntryDTO listHit, int ruleScore, List<String> hitRules) {
        List<RiskFactorDTO> factors = new ArrayList<>();
        Map<String, Integer> w = weights();
        int total = 0;

        // 1) 频率因子（只读观测，不消耗限频额度）
        int freqThreshold = config.getInt(RiskKeys.SCORE_FREQ_THRESHOLD, RiskKeys.DEFAULT_SCORE_FREQ_THRESHOLD);
        long freqWindow = config.getLong(RiskKeys.SCORE_FREQ_WINDOW, RiskKeys.DEFAULT_SCORE_FREQ_WINDOW);
        long count = -1;
        try {
            count = rateLimiter.countInWindow(ctx.getPayerId(), ctx.getNow(), freqWindow);
        } catch (Exception e) {
            log.warn("频率因子观测失败（按无信息处理）: {}", e.getMessage());
        }
        int freqWeight = w.getOrDefault("FREQ", RiskKeys.WEIGHT_FREQ);
        int freqScore = count >= freqThreshold && freqThreshold > 0 ? freqWeight : 0;
        factors.add(RiskFactorDTO.of("FREQ", "交易频率", freqWeight, String.valueOf(count), freqScore,
                "窗口 " + freqWindow + "ms 内笔数 ≥ " + freqThreshold + " 才加分"));
        total += freqScore;

        // 2) 金额偏离因子（需显式开启历史累计 + 足够样本）
        int devWeight = w.getOrDefault("AMOUNT_DEV", RiskKeys.WEIGHT_AMOUNT_DEV);
        int ratio = config.getInt(RiskKeys.SCORE_AMOUNT_RATIO, RiskKeys.DEFAULT_SCORE_AMOUNT_RATIO);
        int minSample = config.getInt(RiskKeys.SCORE_AMOUNT_MIN_SAMPLE, RiskKeys.DEFAULT_SCORE_AMOUNT_MIN_SAMPLE);
        double deviation = 0d;
        int devScore = 0;
        String devDetail = "历史累计未开启（默认关闭，正常流量零开销）";
        if (amountHistoryEnabled()) {
            long[] usage = quotaService.dayUsage(ctx.getPayerId(), ctx.getNow());
            long usedAmount = usage[0];
            long usedCount = usage[1];
            if (usedCount >= minSample && usedCount > 0) {
                double avg = (double) usedAmount / usedCount;
                deviation = avg > 0 ? ctx.getAmount() / avg : 0d;
                devScore = deviation > ratio ? devWeight : 0;
                devDetail = "日累计 " + usedAmount + " 分/" + usedCount + " 笔，均额 " + Math.round(avg)
                        + " 分，偏离 " + String.format("%.2f", deviation) + " 倍（阈值 " + ratio + "）";
            } else {
                devDetail = "历史样本 " + usedCount + " 笔 < 下限 " + minSample + "，不评分（冷启动保护）";
            }
        }
        ctx.setAmountDeviation(deviation);
        factors.add(RiskFactorDTO.of("AMOUNT_DEV", "金额偏离", devWeight,
                String.format("%.2f", deviation), devScore, devDetail));
        total += devScore;

        // 3) 名单因子（灰名单加分；黑名单已在名单环节直接处置；白名单不进入评分）
        int listWeight = w.getOrDefault("LIST", RiskKeys.WEIGHT_LIST);
        int listScore = listHit != null && "GRAY".equals(listHit.getListType()) ? listWeight : 0;
        factors.add(RiskFactorDTO.of("LIST", "名单", listWeight,
                listHit == null ? "none" : listHit.getListType() + ":" + listHit.getValueType(),
                listScore, listHit == null ? "未命中名单" : "命中 " + listHit.getListType() + " 名单"));
        total += listScore;

        // 4) 设备因子（R-5 扩展：既有风险设备名单 + 设备画像判定，共用同一 DEVICE 权重，不另起体系）
        int deviceWeight = w.getOrDefault("DEVICE", RiskKeys.WEIGHT_DEVICE);
        boolean deviceListHit = !ctx.getDeviceId().isEmpty() && riskDevices().contains(ctx.getDeviceId());
        boolean deviceHit = deviceListHit || ctx.isDeviceProfileRisk();
        String deviceDetail;
        if (deviceListHit) {
            deviceDetail = "设备在风险设备名单内";
        } else if (ctx.isDeviceProfileRisk()) {
            deviceDetail = "设备画像命中：" + ctx.getDeviceProfileDetail();
        } else {
            deviceDetail = "设备未命中风险名单（缺省不加分）";
            if (ctx.isDeviceProfileLoaded()) {
                deviceDetail = deviceDetail + "；画像观测：" + ctx.getDeviceProfileDetail();
            }
        }
        factors.add(RiskFactorDTO.of("DEVICE", "风险设备", deviceWeight,
                ctx.getDeviceId().isEmpty() ? "none" : ctx.getDeviceId(), deviceHit ? deviceWeight : 0,
                deviceDetail));
        total += deviceHit ? deviceWeight : 0;

        // 5) IP 因子（R-5 扩展：既有风险 IP 名单 + IP 画像判定，共用同一 IP 权重，不另起体系）
        int ipWeight = w.getOrDefault("IP", RiskKeys.WEIGHT_IP);
        boolean ipListHit = !ctx.getIp().isEmpty() && riskIps().contains(ctx.getIp());
        boolean ipHit = ipListHit || ctx.isIpProfileRisk();
        String ipDetail;
        if (ipListHit) {
            ipDetail = "IP 在风险 IP 名单内";
        } else if (ctx.isIpProfileRisk()) {
            ipDetail = "IP 画像命中：" + ctx.getIpProfileDetail();
        } else {
            ipDetail = "IP 未命中风险名单（缺省不加分）";
            if (ctx.isIpProfileLoaded()) {
                ipDetail = ipDetail + "；画像观测：" + ctx.getIpProfileDetail();
            }
        }
        factors.add(RiskFactorDTO.of("IP", "风险IP", ipWeight, ctx.getIp().isEmpty() ? "none" : ctx.getIp(),
                ipHit ? ipWeight : 0, ipDetail));
        total += ipHit ? ipWeight : 0;

        // 6) 时段因子
        int timeWeight = w.getOrDefault("TIME", RiskKeys.WEIGHT_TIME);
        boolean timeHit = riskHours().contains(String.valueOf(ctx.getHour()));
        factors.add(RiskFactorDTO.of("TIME", "风险时段", timeWeight, String.valueOf(ctx.getHour()),
                timeHit ? timeWeight : 0, timeHit ? "当前小时在风险时段内" : "时段未命中（默认空=不加分）"));
        total += timeHit ? timeWeight : 0;

        // 7) 规则命中分（R-1 引擎输出）
        if (ruleScore > 0 || (hitRules != null && !hitRules.isEmpty())) {
            factors.add(RiskFactorDTO.of("RULE", "规则命中", ruleScore,
                    hitRules == null ? "" : String.join("|", hitRules), ruleScore,
                    "命中规则贡献分之和"));
            total += ruleScore;
        }
        return new Score(total, factors);
    }
}