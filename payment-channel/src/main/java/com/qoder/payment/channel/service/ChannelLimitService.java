package com.qoder.payment.channel.service;

import com.qoder.payment.channel.config.ChannelConfigKeys;
import com.qoder.payment.channel.dataobject.ChannelConfigDO;
import com.qoder.payment.channel.dataobject.ChannelDailyUsageDO;
import com.qoder.payment.channel.mapper.ChannelDailyUsageMapper;
import com.qoder.payment.channel.spi.ChannelAdapter;
import com.qoder.payment.channel.spi.ChannelAdapterRegistry;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.channel.ChannelLimitDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * CH-5 渠道限额：单笔 + 日累计，<b>受理前</b>校验（超限直接拒绝，不落 channel_trade、不留悬挂单）。
 *
 * <p>生效值 = min(平台配置, 渠道侧 SPI queryLimit)，平台配置由 I-2 DynamicConfig 覆盖 channel_config；
 * 0/null = 不限。日累计落 channel_daily_usage（DB 原子 incr，双 profile 同构、重启不丢），
 * 按 Asia/Shanghai biz_date 分行 → 跨日自然重置（无需定时清零任务）。
 *
 * <p>与风控限额（R-2，用户/商户侧）区分：本服务是<b>渠道侧</b>约束，二者串联互不替代。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelLimitService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ChannelConfigService configService;
    private final ChannelDailyUsageMapper usageMapper;
    private final ChannelAdapterRegistry registry;
    private final MaintenanceWindowService maintenanceWindowService;
    private final DynamicConfig config;

    public static String bizDate(long now) {
        return DAY.format(Instant.ofEpochMilli(now).atZone(MaintenanceWindowService.ZONE));
    }

    /** 生效限额 [single, daily]，0=不限；取平台配置与渠道侧约束的更严者。 */
    public long[] effectiveLimits(String channelCode) {
        ChannelConfigDO c = configService.get(channelCode);
        long single = c == null || c.getSingleLimit() == null ? 0L : c.getSingleLimit();
        long daily = c == null || c.getDailyLimit() == null ? 0L : c.getDailyLimit();
        ChannelAdapter adapter = registry.find(channelCode);
        if (adapter != null) {
            ChannelLimitDTO side = adapter.queryLimit();
            if (side != null) {
                single = tighter(single, side.getSingleLimit());
                daily = tighter(daily, side.getDailyLimit());
            }
        }
        return new long[]{single, daily};
    }

    private static long tighter(long a, Long b) {
        long v = b == null ? 0L : b;
        if (a <= 0) {
            return Math.max(v, 0L);
        }
        return v <= 0 ? a : Math.min(a, v);
    }

    /**
     * 受理前限额校验（硬拒绝）。超限抛 BIZ_ERROR，由 TCC Try 失败 → Cancel 回滚，
     * 支付单 FAILED、订单保持 CREATED、无账务单据（与既有渠道拒绝路径同构）。
     */
    public void checkBeforeAccept(String channelCode, long amount) {
        long[] limits = effectiveLimits(channelCode);
        if (limits[0] > 0 && amount > limits[0]) {
            throw new BizException(ResultCode.BIZ_ERROR, "渠道限额拒绝: 单笔金额 " + amount + " 分超出渠道 "
                    + channelCode + " 单笔限额 " + limits[0] + " 分");
        }
        if (limits[1] > 0) {
            long used = usedAmount(channelCode, bizDate(System.currentTimeMillis()));
            if (used + amount > limits[1]) {
                throw new BizException(ResultCode.BIZ_ERROR, "渠道限额拒绝: 当日累计 " + (used + amount)
                        + " 分超出渠道 " + channelCode + " 日限额 " + limits[1] + " 分");
            }
        }
    }

    /** 受理成功后累计（幂等由 channel_trade.biz_no PK 保证：一单只受理一次 → 只累计一次）。 */
    public void accumulate(String channelCode, long amount) {
        String day = bizDate(System.currentTimeMillis());
        if (usageMapper.select(channelCode, day) == null) {
            ChannelDailyUsageDO usage = new ChannelDailyUsageDO();
            usage.setChannelCode(channelCode);
            usage.setBizDate(day);
            usage.setUsedAmount(0L);
            usage.setUsedCount(0L);
            try {
                usageMapper.insert(usage);
            } catch (DuplicateKeyException e) {
                log.debug("渠道日累计行并发已建 channelCode={} bizDate={}", channelCode, day);
            }
        }
        usageMapper.incr(channelCode, day, amount);
    }

    public long usedAmount(String channelCode, String bizDate) {
        ChannelDailyUsageDO usage = usageMapper.select(channelCode, bizDate);
        return usage == null || usage.getUsedAmount() == null ? 0L : usage.getUsedAmount();
    }

    /** 限额/维护/可用性视图（控制台与验收脚本观测口径）。 */
    public ChannelLimitDTO view(String channelCode) {
        ChannelConfigDO c = configService.require(channelCode);
        long[] limits = effectiveLimits(channelCode);
        String day = bizDate(System.currentTimeMillis());
        ChannelDailyUsageDO usage = usageMapper.select(channelCode, day);
        long used = usage == null || usage.getUsedAmount() == null ? 0L : usage.getUsedAmount();
        long count = usage == null || usage.getUsedCount() == null ? 0L : usage.getUsedCount();
        boolean maintenance = maintenanceWindowService.inMaintenance(channelCode);
        boolean registered = registry.contains(channelCode);
        boolean enabled = "ENABLED".equalsIgnoreCase(c.getStatus());

        ChannelLimitDTO dto = new ChannelLimitDTO();
        dto.setChannelCode(channelCode);
        dto.setSingleLimit(limits[0] > 0 ? limits[0] : null);
        dto.setDailyLimit(limits[1] > 0 ? limits[1] : null);
        dto.setBizDate(day);
        dto.setUsedAmount(used);
        dto.setUsedCount(count);
        dto.setDailyRemaining(limits[1] > 0 ? Math.max(0L, limits[1] - used) : null);
        dto.setMaintenanceStart(c.getMaintenanceStart());
        dto.setMaintenanceEnd(c.getMaintenanceEnd());
        dto.setInMaintenance(maintenance);
        dto.setAvailable(enabled && !maintenance && registered);
        dto.setReason(!registered ? "适配器未注册"
                : !enabled ? "渠道状态 " + c.getStatus()
                : maintenance ? "维护窗口内 " + maintenanceWindowService.describe(channelCode)
                : "AVAILABLE");
        dto.setLimitSource(limitSource(channelCode));
        dto.setMaintenanceSource(maintenanceSource(channelCode));
        return dto;
    }

    private String limitSource(String code) {
        boolean dynamic = present(ChannelConfigKeys.singleLimit(code)) || present(ChannelConfigKeys.dailyLimit(code));
        ChannelAdapter adapter = registry.find(code);
        boolean side = adapter != null && adapter.queryLimit() != null;
        return dynamic ? (side ? "dynamic-config+adapter" : "dynamic-config")
                : (side ? "channel-config+adapter" : "channel-config");
    }

    private String maintenanceSource(String code) {
        if (present(ChannelConfigKeys.maintenanceStart(code)) || present(ChannelConfigKeys.maintenanceEnd(code))
                || present(ChannelConfigKeys.maintenanceWindow(code)) || present(ChannelConfigKeys.status(code))) {
            return "dynamic-config";
        }
        return "channel-config";
    }

    private boolean present(String key) {
        String v = config.get(key, null);
        return v != null && !v.isBlank();
    }
}