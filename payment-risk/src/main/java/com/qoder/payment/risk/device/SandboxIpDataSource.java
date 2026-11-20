package com.qoder.payment.risk.device;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.risk.config.RiskPlusKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R-5 <b>离线沙箱 IP 数据源</b>（归属地 / 代理 / IDC 判定）。
 *
 * <p><b>红线</b>：全部数据来自内置 CIDR 沙箱表（RFC 文档网段 + 常见公开 resolver/CDN/Tor 出口网段的
 * 离线快照），可经 {@code risk.ip.sandbox.json} 覆盖；<b>不发起任何网络请求、不连外部威胁情报 API</b>，
 * local/docker 双 profile 行为完全一致（docker 只是配置源换成 Apollo，数据仍是离线表）。
 * <p>未匹配 → region="未知"、country=""（空串），<b>跨境规则不把未知当跨境</b>，避免误报。
 */
@Slf4j
@Component
public class SandboxIpDataSource {

    /** 沙箱条目。 */
    public record Entry(String cidr, String region, String country, boolean proxy, boolean idc, int prefix) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 内置离线沙箱表（ longest-prefix 匹配）。 */
    private static final List<Entry> BUILTIN = List.of(
            new Entry("127.0.0.0/8", "本地回环", "CN", false, false, 8),
            new Entry("10.0.0.0/8", "内网", "CN", false, false, 8),
            new Entry("172.16.0.0/12", "内网", "CN", false, false, 12),
            new Entry("192.168.0.0/16", "内网", "CN", false, false, 16),
            new Entry("100.64.0.0/10", "运营商级 NAT", "CN", true, false, 10),
            new Entry("198.18.0.0/15", "基准测试网段", "CN", true, false, 15),
            new Entry("203.0.113.0/24", "文档网段 TEST-NET-3", "CN", false, true, 24),
            new Entry("198.51.100.0/24", "文档网段 TEST-NET-2", "US", false, true, 24),
            new Entry("114.114.114.0/24", "中国·江苏·南京", "CN", false, false, 24),
            new Entry("223.5.5.0/24", "中国·浙江·杭州", "CN", false, false, 24),
            new Entry("1.2.4.0/24", "中国·北京", "CN", false, false, 24),
            new Entry("8.8.8.0/24", "美国·加州·山景城", "US", false, true, 24),
            new Entry("104.16.0.0/12", "美国·CDN 边缘", "US", false, true, 12),
            new Entry("103.21.244.0/22", "新加坡", "SG", false, true, 22),
            new Entry("45.83.0.0/16", "荷兰·阿姆斯特丹", "NL", true, true, 16),
            new Entry("185.220.101.0/24", "德国·Tor 出口节点", "DE", true, true, 24));

    private static final Entry UNKNOWN = new Entry("0.0.0.0/0", "未知", "", false, false, 0);

    private final DynamicConfig config;
    private volatile List<Entry> entries = BUILTIN;
    private volatile String lastRaw = "";
    private volatile String source = "SANDBOX";

    public SandboxIpDataSource(DynamicConfig config) {
        this.config = config;
        refresh();
    }

    /** 配置覆盖（热更）；解析失败保留内置表（last-known-good），绝不因配置手误影响画像。 */
    public void refresh() {
        String raw = config.get(RiskPlusKeys.IP_SANDBOX_JSON, null);
        String key = raw == null ? "" : raw;
        if (key.equals(lastRaw)) {
            return;
        }
        lastRaw = key;
        if (raw == null || raw.isBlank()) {
            entries = BUILTIN;
            source = "SANDBOX";
            return;
        }
        try {
            List<Map<?, ?>> parsed = MAPPER.readValue(raw,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
            List<Entry> next = new ArrayList<>();
            for (Map<?, ?> m : parsed) {
                String cidr = str(m.get("cidr"));
                int prefix = prefixOf(cidr);
                if (prefix < 0) {
                    continue;
                }
                next.add(new Entry(cidr, str(m.get("region")), str(m.get("country")),
                        bool(m.get("proxy")), bool(m.get("idc")), prefix));
            }
            next.addAll(BUILTIN);
            entries = List.copyOf(next);
            source = "CONFIG";
            log.info("R-5 离线沙箱 IP 表配置覆盖生效 entries={}（仍为离线数据，无外部请求）", next.size());
        } catch (Exception e) {
            log.warn("R-5 沙箱 IP 表配置解析失败（保留内置离线表）: {}", e.getMessage());
            entries = BUILTIN;
            source = "SANDBOX";
        }
    }

    public String source() {
        return source;
    }

    public List<Map<String, Object>> describe() {
        refresh();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cidr", e.cidr());
            m.put("region", e.region());
            m.put("country", e.country());
            m.put("proxy", e.proxy());
            m.put("idc", e.idc());
            out.add(m);
        }
        return out;
    }

    /** 最长前缀匹配；IPv6/非法 IP → UNKNOWN（不视为跨境、不视为代理）。 */
    public Entry resolve(String ip) {
        refresh();
        long addr = toLong(ip);
        if (addr < 0) {
            return UNKNOWN;
        }
        Entry best = UNKNOWN;
        for (Entry e : entries) {
            if (e.prefix() <= best.prefix()) {
                continue;
            }
            long net = toLong(e.cidr().split("/")[0]);
            if (net < 0) {
                continue;
            }
            long mask = e.prefix() == 0 ? 0L : (0xFFFFFFFFL << (32 - e.prefix())) & 0xFFFFFFFFL;
            if ((addr & mask) == (net & mask)) {
                best = e;
            }
        }
        return best;
    }

    private static int prefixOf(String cidr) {
        if (cidr == null || !cidr.contains("/")) {
            return -1;
        }
        try {
            int p = Integer.parseInt(cidr.split("/")[1].trim());
            return p >= 0 && p <= 32 && toLong(cidr.split("/")[0]) >= 0 ? p : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static long toLong(String ip) {
        if (ip == null) {
            return -1;
        }
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) {
            return -1;
        }
        long v = 0;
        for (String p : parts) {
            try {
                int n = Integer.parseInt(p.trim());
                if (n < 0 || n > 255) {
                    return -1;
                }
                v = (v << 8) | n;
            } catch (Exception e) {
                return -1;
            }
        }
        return v;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean bool(Object o) {
        return o != null && Boolean.parseBoolean(String.valueOf(o));
    }
}