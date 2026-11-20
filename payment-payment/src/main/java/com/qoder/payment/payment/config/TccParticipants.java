package com.qoder.payment.payment.config;

import com.qoder.payment.facade.tcc.TccParticipantFacade;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * I-6 TCC 参与者自注册：参与者以 Dubbo {@code group=<域名>} 暴露 {@link TccParticipantFacade}，
 * 网关侧不再为每个新参与者域硬编码字段。
 *
 * <p>已知三个参与者（accounting/channel/trade）保留 {@code @DubboReference} 作保底，确保
 * 注册中心/配置尚未就绪时协调器始终可用；**新增参与者域**只需在配置
 * {@code payment.tcc.participant-groups} 追加 group（逗号分隔，可经 Apollo 热更），
 * 本类按需建 {@link ReferenceConfig} 引用并缓存，**无需改代码**。
 *
 * <p>返回活视图：协调器只按名 {@code get(group)}，按需解析，避免启动期 eagerly 建引用失败。
 */
@Component
public class TccParticipants {

    private static final Logger log = LoggerFactory.getLogger(TccParticipants.class);

    @DubboReference(group = "accounting")
    private TccParticipantFacade accounting;

    @DubboReference(group = "channel")
    private TccParticipantFacade channel;

    @DubboReference(group = "trade")
    private TccParticipantFacade trade;

    private final Environment env;
    private final Map<String, TccParticipantFacade> extra = new ConcurrentHashMap<>();

    public TccParticipants(Environment env) {
        this.env = env;
    }

    /** 活视图：get 按需解析（保底 + 配置追加的 group）。 */
    public Map<String, TccParticipantFacade> asMap() {
        return new java.util.AbstractMap<String, TccParticipantFacade>() {
            @Override
            public TccParticipantFacade get(Object key) {
                return facade(String.valueOf(key));
            }

            @Override
            public Set<Entry<String, TccParticipantFacade>> entrySet() {
                Map<String, TccParticipantFacade> snapshot = new LinkedHashMap<>();
                snapshot.put("accounting", accounting);
                snapshot.put("channel", channel);
                snapshot.put("trade", trade);
                for (String g : configuredGroups()) {
                    snapshot.put(g, facade(g));
                }
                return snapshot.entrySet();
            }
        };
    }

    public TccParticipantFacade facade(String group) {
        switch (group) {
            case "accounting": return accounting;
            case "channel": return channel;
            case "trade": return trade;
            default: return extra.computeIfAbsent(group, this::reference);
        }
    }

    private java.util.List<String> configuredGroups() {
        String raw = env.getProperty("payment.tcc.participant-groups", "");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String g : raw.split(",")) {
            String t = g.trim();
            if (!t.isEmpty() && !"accounting".equals(t) && !"channel".equals(t) && !"trade".equals(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private TccParticipantFacade reference(String group) {
        ReferenceConfig<TccParticipantFacade> rc = new ReferenceConfig<>();
        rc.setInterface(TccParticipantFacade.class);
        rc.setGroup(group);
        rc.setCheck(false);
        log.info("TCC 参与者自注册建立引用 group={}", group);
        return rc.get();
    }
}