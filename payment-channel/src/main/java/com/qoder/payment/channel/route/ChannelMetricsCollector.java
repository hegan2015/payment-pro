package com.qoder.payment.channel.route;

import com.qoder.payment.channel.config.ChannelConfigKeys;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.facade.channel.ChannelMetricsDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CH-3 渠道指标采集：滑动窗口成功率 / 平均耗时 + 失败率熔断（接 I-8 MetricsSupport）。
 *
 * <p>双 profile 同构：进程内窗口（零依赖，local 与 docker 一致），同时把
 * {@code payment.channel.accept_*} 指标写入 Micrometer 全局注册表供 Prometheus 抓取。
 * 多实例下各实例独立统计（路由是本地决策，无需全局一致；P2 可换 Redis 聚合）。
 *
 * <p>熔断：窗口内样本数 ≥ min-samples 且失败率 ≥ fail-rate → 打开 cooldown 毫秒，
 * 期间路由硬过滤该渠道（自动绕行备选）；到期自动半开（重新计入样本）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelMetricsCollector {

    private final DynamicConfig config;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private record Sample(long at, boolean success, long costMs) {
    }

    private static final class Window {
        private final Deque<Sample> samples = new ArrayDeque<>();
        private long breakerUntil;
    }

    /** 记录一次渠道受理结果（成功=渠道给出终态或受理成功；失败=拒绝/不可达）。 */
    public void record(String channelCode, boolean success, long costMs) {
        long now = System.currentTimeMillis();
        Window w = windows.computeIfAbsent(channelCode, k -> new Window());
        long windowMs = config.getLong(ChannelConfigKeys.ROUTE_WINDOW_MS, ChannelConfigKeys.DEFAULT_WINDOW_MS);
        int minSamples = config.getInt(ChannelConfigKeys.ROUTE_BREAKER_MIN_SAMPLES, ChannelConfigKeys.DEFAULT_BREAKER_MIN_SAMPLES);
        double failRate = failRateThreshold();
        long cooldown = config.getLong(ChannelConfigKeys.ROUTE_BREAKER_COOLDOWN_MS, ChannelConfigKeys.DEFAULT_BREAKER_COOLDOWN_MS);
        synchronized (w) {
            w.samples.addLast(new Sample(now, success, Math.max(0L, costMs)));
            prune(w, now, windowMs);
            if (!success && w.samples.size() >= minSamples) {
                long fails = w.samples.stream().filter(s -> !s.success()).count();
                double rate = (double) fails / w.samples.size();
                if (rate >= failRate) {
                    w.breakerUntil = now + cooldown;
                    log.warn("[告警] 渠道熔断打开 channelCode={} failRate={} samples={} cooldownMs={}",
                            channelCode, String.format("%.2f", rate), w.samples.size(), cooldown);
                    MetricsSupport.count("channel_breaker_open", "channel", channelCode);
                }
            }
        }
        MetricsSupport.count("channel_accept", "channel", channelCode, "status", success ? "success" : "fail");
        MetricsSupport.recordMillis("channel_accept_duration", costMs, "channel", channelCode);
    }

    public boolean breakerOpen(String channelCode) {
        Window w = windows.get(channelCode);
        return w != null && w.breakerUntil > System.currentTimeMillis();
    }

    /** 人工/混沌置熔断（验收与运维用）；ms<=0 表示立即解除。 */
    public void tripBreaker(String channelCode, long ms) {
        Window w = windows.computeIfAbsent(channelCode, k -> new Window());
        synchronized (w) {
            w.breakerUntil = ms <= 0 ? 0L : System.currentTimeMillis() + ms;
        }
        log.info("渠道熔断人工设置 channelCode={} ms={}", channelCode, ms);
    }

    /** 清空窗口统计（验收隔离用，不影响任何业务数据）。 */
    public void reset(String channelCode) {
        windows.remove(channelCode);
    }

    public ChannelMetricsDTO snapshot(String channelCode) {
        long now = System.currentTimeMillis();
        long windowMs = config.getLong(ChannelConfigKeys.ROUTE_WINDOW_MS, ChannelConfigKeys.DEFAULT_WINDOW_MS);
        Window w = windows.get(channelCode);
        ChannelMetricsDTO dto = new ChannelMetricsDTO();
        dto.setChannelCode(channelCode);
        dto.setBreakerOpen(breakerOpen(channelCode));
        if (w == null) {
            dto.setSuccessCount(0L);
            dto.setFailCount(0L);
            dto.setSampleCount(0L);
            // 无样本：乐观默认成功率 1.0（新渠道不因冷启动被饿死），耗时 0
            dto.setSuccessRate(1.0);
            dto.setAvgCostMs(0L);
            dto.setWindowStart(now - windowMs);
            return dto;
        }
        List<Sample> copy;
        synchronized (w) {
            prune(w, now, windowMs);
            copy = new ArrayList<>(w.samples);
        }
        long success = copy.stream().filter(Sample::success).count();
        long fail = copy.size() - success;
        long totalCost = copy.stream().mapToLong(Sample::costMs).sum();
        dto.setSuccessCount(success);
        dto.setFailCount(fail);
        dto.setSampleCount((long) copy.size());
        dto.setSuccessRate(copy.isEmpty() ? 1.0 : (double) success / copy.size());
        dto.setAvgCostMs(copy.isEmpty() ? 0L : totalCost / copy.size());
        dto.setWindowStart(now - windowMs);
        return dto;
    }

    private static void prune(Window w, long now, long windowMs) {
        while (!w.samples.isEmpty() && now - w.samples.peekFirst().at() > windowMs) {
            w.samples.pollFirst();
        }
    }

    /** 失败率阈值（动态配置，非法值回落代码默认 0.5，绝不因误配置阻断支付）。 */
    private double failRateThreshold() {
        String raw = config.get(ChannelConfigKeys.ROUTE_BREAKER_FAIL_RATE, null);
        if (raw == null || raw.isBlank()) {
            return ChannelConfigKeys.DEFAULT_BREAKER_FAIL_RATE;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            return v > 0 && v <= 1 ? v : ChannelConfigKeys.DEFAULT_BREAKER_FAIL_RATE;
        } catch (NumberFormatException e) {
            return ChannelConfigKeys.DEFAULT_BREAKER_FAIL_RATE;
        }
    }
}