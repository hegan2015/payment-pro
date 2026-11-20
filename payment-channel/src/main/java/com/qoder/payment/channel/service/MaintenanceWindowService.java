package com.qoder.payment.channel.service;

import com.qoder.payment.channel.config.ChannelConfigKeys;
import com.qoder.payment.channel.dataobject.ChannelConfigDO;
import com.qoder.payment.common.config.DynamicConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * CH-5 维护窗口：窗口内渠道不可用，路由硬过滤绕行（受理前排除，不留悬挂单）。
 *
 * <p>两种窗口并联（任一命中即维护中），均经 I-2 DynamicConfig 动态下发、改配置不重启：
 * <ul>
 *   <li>epoch 区间 {@code channel.<code>.maintenance-start/end}（毫秒，DB channel_config 同名列兜底）；</li>
 *   <li>每日循环 {@code channel.<code>.maintenance-window}="HH:mm-HH:mm"（Asia/Shanghai，跨午夜支持）。</li>
 * </ul>
 * 窗口值非法（解析失败）→ 视为无窗口 + warn，绝不因误配置阻断支付（降级红线）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaintenanceWindowService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ChannelConfigService configService;
    private final DynamicConfig config;

    public boolean inMaintenance(String channelCode) {
        return inMaintenance(channelCode, System.currentTimeMillis());
    }

    public boolean inMaintenance(String channelCode, long now) {
        ChannelConfigDO c = configService.get(channelCode);
        if (c == null) {
            return false;
        }
        if ("MAINTENANCE".equalsIgnoreCase(c.getStatus())) {
            return true;
        }
        long start = c.getMaintenanceStart() == null ? 0L : c.getMaintenanceStart();
        long end = c.getMaintenanceEnd() == null ? 0L : c.getMaintenanceEnd();
        if (start > 0 && end > 0 && now >= start && now <= end) {
            return true;
        }
        return inDailyWindow(channelCode, now);
    }

    /** 每日循环窗口（动态配置键，DB 不存）：非法值忽略。 */
    private boolean inDailyWindow(String channelCode, long now) {
        String raw = config.get(ChannelConfigKeys.maintenanceWindow(channelCode), null);
        if (raw == null || raw.isBlank() || !raw.contains("-")) {
            return false;
        }
        try {
            String[] parts = raw.trim().split("-");
            LocalTime from = LocalTime.parse(parts[0].trim());
            LocalTime to = LocalTime.parse(parts[1].trim());
            LocalTime cur = Instant.ofEpochMilli(now).atZone(ZONE).toLocalTime();
            if (from.equals(to)) {
                return false;
            }
            return from.isBefore(to)
                    ? (!cur.isBefore(from) && !cur.isAfter(to))
                    : (!cur.isBefore(from) || !cur.isAfter(to));
        } catch (Exception e) {
            log.warn("维护窗口配置非法（忽略）channelCode={} value={}: {}", channelCode, raw, e.getMessage());
            return false;
        }
    }

    /** 窗口描述（观测用）：epoch 区间优先，其次每日循环。 */
    public String describe(String channelCode) {
        ChannelConfigDO c = configService.get(channelCode);
        if (c == null) {
            return "UNCONFIGURED";
        }
        long start = c.getMaintenanceStart() == null ? 0L : c.getMaintenanceStart();
        long end = c.getMaintenanceEnd() == null ? 0L : c.getMaintenanceEnd();
        if (start > 0 && end > 0) {
            return start + "~" + end;
        }
        String daily = config.get(ChannelConfigKeys.maintenanceWindow(channelCode), null);
        return daily == null || daily.isBlank() ? "NONE" : "DAILY " + daily;
    }
}