package com.qoder.payment.channel.service;

import com.qoder.payment.channel.config.ChannelConfigKeys;
import com.qoder.payment.channel.config.ChannelSchemaUpgrade;
import com.qoder.payment.channel.dataobject.ChannelConfigDO;
import com.qoder.payment.channel.mapper.ChannelConfigMapper;
import com.qoder.payment.channel.spi.ChannelAdapter;
import com.qoder.payment.channel.spi.ChannelAdapterRegistry;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * CH-2a 渠道配置服务：channel_config 幂等 seeding（只 INSERT 缺失行，绝不改写既有行）
 * + 生效值解析（I-2 DynamicConfig 覆盖 DB，配置中心不可达用 DB/代码默认值兜底）。
 *
 * <p>默认配置保证「同步模拟银行 MOCK_BANK 优先」：备用渠道权重更低、沙箱渠道异步 opt-in、
 * 参考空实现 DISABLED —— 默认 stack 的 CARD 支付路径与重构前逐字节一致（§0.7 红线）。
 */
@Slf4j
@Service
public class ChannelConfigService implements InitializingBean {

    private final ChannelConfigMapper mapper;
    private final ChannelAdapterRegistry registry;
    private final DynamicConfig config;

    public ChannelConfigService(ChannelConfigMapper mapper, ChannelAdapterRegistry registry,
                                DynamicConfig config, ChannelSchemaUpgrade schemaUpgrade) {
        this.mapper = mapper;
        this.registry = registry;
        this.config = config;
        // 构造期依赖：保证 docker 现存库先补列/建表，再 seeding（local schema.sql 已建好）
        Objects.requireNonNull(schemaUpgrade, "渠道库增量升级必须先于配置 seeding");
    }

    @Override
    public void afterPropertiesSet() {
        long now = System.currentTimeMillis();
        for (ChannelAdapter adapter : registry.all()) {
            String code = adapter.channelCode();
            if (mapper.selectByCode(code) != null) {
                continue;
            }
            ChannelConfigDO seed = defaults(code, adapter, now);
            mapper.insert(seed);
            log.info("CH-2a 渠道配置 seeding channelCode={} adapter={} status={} env={}",
                    code, adapter.adapterType(), seed.getStatus(), seed.getEnv());
        }
    }

    /** 已知渠道的出厂默认；未知新适配器一律 DISABLED（插上即注册，但需显式启用才接流量）。 */
    private ChannelConfigDO defaults(String code, ChannelAdapter adapter, long now) {
        ChannelConfigDO c = new ChannelConfigDO();
        c.setChannelCode(code);
        c.setAdapterType(adapter.adapterType());
        c.setEnv(adapter.env());
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        c.setMaintenanceStart(0L);
        c.setMaintenanceEnd(0L);
        c.setConfig("{\"env\":\"" + adapter.env() + "\",\"secretRef\":\"channel-" + code.toLowerCase() + "\"}");
        switch (code) {
            case "MOCK_BANK" -> {
                c.setName("模拟银行（同步，local 降级与混沌靶）");
                c.setStatus("ENABLED");
                c.setFeeBps(60);
                c.setWeight(100);
            }
            case "MOCK_BANK_B" -> {
                c.setName("模拟银行 B（同步备选，故障切换靶）");
                c.setStatus("ENABLED");
                c.setFeeBps(30);
                c.setWeight(10);
            }
            case "SANDBOX_BANK" -> {
                c.setName("沙箱银行（异步受理+回执，无真实银行凭据）");
                c.setStatus("ENABLED");
                c.setFeeBps(55);
                c.setWeight(50);
            }
            default -> {
                c.setName(code + "（SPI 参考实现）");
                c.setStatus("DISABLED");
                c.setFeeBps(60);
                c.setWeight(1);
            }
        }
        return c;
    }

    public List<ChannelConfigDO> list() {
        List<ChannelConfigDO> out = new ArrayList<>();
        for (ChannelConfigDO base : mapper.selectAll()) {
            out.add(applyOverrides(base));
        }
        return out;
    }

    public ChannelConfigDO get(String code) {
        ChannelConfigDO base = code == null ? null : mapper.selectByCode(code);
        return base == null ? null : applyOverrides(base);
    }

    public ChannelConfigDO require(String code) {
        ChannelConfigDO c = get(code);
        if (c == null) {
            throw new BizException(ResultCode.NOT_FOUND, "渠道未配置: " + code);
        }
        return c;
    }

    /** 生效值 = DB 配置 + DynamicConfig 覆盖（限额/维护/状态/权重/成本动态下发，改配置不重启）。 */
    private ChannelConfigDO applyOverrides(ChannelConfigDO base) {
        ChannelConfigDO eff = new ChannelConfigDO();
        BeanUtils.copyProperties(base, eff);
        String code = base.getChannelCode();
        eff.setStatus(config.get(ChannelConfigKeys.status(code), base.getStatus()));
        eff.setWeight((int) config.getLong(ChannelConfigKeys.weight(code), nz(base.getWeight())));
        eff.setFeeBps((int) config.getLong(ChannelConfigKeys.feeBps(code), nz(base.getFeeBps())));
        eff.setSingleLimit(overrideLimit(ChannelConfigKeys.singleLimit(code), base.getSingleLimit()));
        eff.setDailyLimit(overrideLimit(ChannelConfigKeys.dailyLimit(code), base.getDailyLimit()));
        eff.setMaintenanceStart(config.getLong(ChannelConfigKeys.maintenanceStart(code), nz(base.getMaintenanceStart())));
        eff.setMaintenanceEnd(config.getLong(ChannelConfigKeys.maintenanceEnd(code), nz(base.getMaintenanceEnd())));
        return eff;
    }

    /** 动态限额：键存在则以其为准（<=0 视为不限=null）；缺失沿用 DB。 */
    private Long overrideLimit(String key, Long dbValue) {
        String raw = config.get(key, null);
        if (raw == null || raw.isBlank()) {
            return dbValue;
        }
        try {
            long v = Long.parseLong(raw.trim());
            return v <= 0 ? null : v;
        } catch (NumberFormatException e) {
            log.warn("渠道限额动态配置解析失败 key={} value={}，沿用 DB 值", key, raw);
            return dbValue;
        }
    }

    public ChannelConfigDO setLimits(String code, Long singleLimit, Long dailyLimit) {
        require(code);
        mapper.updateLimits(code, normalize(singleLimit), normalize(dailyLimit), System.currentTimeMillis());
        return require(code);
    }

    public ChannelConfigDO setMaintenance(String code, Long start, Long end) {
        require(code);
        long s = start == null ? 0L : start;
        long e = end == null ? 0L : end;
        if (s > 0 && e > 0 && e < s) {
            throw new BizException(ResultCode.PARAM_ERROR, "维护窗口结束时间不得早于开始时间");
        }
        mapper.updateMaintenance(code, s, e, System.currentTimeMillis());
        return require(code);
    }

    public ChannelConfigDO setStatus(String code, String status) {
        require(code);
        if (status == null || !List.of("ENABLED", "DISABLED", "MAINTENANCE").contains(status.toUpperCase())) {
            throw new BizException(ResultCode.PARAM_ERROR, "status 仅支持 ENABLED/DISABLED/MAINTENANCE");
        }
        mapper.updateStatus(code, status.toUpperCase(), System.currentTimeMillis());
        return require(code);
    }

    public ChannelConfigDO setScoreFactors(String code, Integer weight, Integer feeBps) {
        ChannelConfigDO current = require(code);
        mapper.updateScoreFactors(code, weight == null ? current.getWeight() : weight,
                feeBps == null ? current.getFeeBps() : feeBps, System.currentTimeMillis());
        return require(code);
    }

    private static Long normalize(Long v) {
        return v == null || v <= 0 ? null : v;
    }

    private static long nz(Number v) {
        return v == null ? 0L : v.longValue();
    }
}