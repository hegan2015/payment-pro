package com.qoder.payment.risk.aml;

import com.qoder.payment.facade.risk.AmlScreeningDTO;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * R-6 名单筛查（制裁 SANCTION / 政要 PEP）——<b>沙箱名单</b>。
 *
 * <p>内置离线沙箱条目启动时 seed（仅当表为空，只 INSERT 不覆盖既有行），运营可增删（软删留痕）。
 * <b>不连任何外部制裁/PEP 数据服务</b>，local/docker 双 profile 行为一致。
 * <p>匹配口径：EXACT（名称全等）&gt; ALIAS（别名全等）&gt; CONTAINS（长度 ≥4 的包含匹配），
 * 大小写与首尾空白归一。命中与否都写 {@code aml_screening} 留痕，可追溯。
 */
@Slf4j
@Service
public class AmlWatchlistService {

    /** 名单条目（内存快照）。 */
    public record Entry(String id, String listType, String name, List<String> aliases, String country) {
    }

    /** 命中结果。 */
    public record Hit(boolean hit, String entryId, String matchType, String listType) {
        static final Hit MISS = new Hit(false, null, null, null);
    }

    private static final long TTL_MS = 5_000L;

    private final AmlStore store;
    private volatile List<Entry> cache = List.of();
    private volatile long loadedAt;

    public AmlWatchlistService(AmlStore store) {
        this.store = store;
    }

    @PostConstruct
    public void seed() {
        try {
            if (store.watchlistCount() > 0) {
                refresh();
                return;
            }
            String[][] sandbox = {
                    {"SANCTION", "SANDBOX-SANCTION-ENTITY-A", "制裁测试实体甲,Sanction Entity A", "XX"},
                    {"SANCTION", "SANDBOX-SANCTION-ENTITY-B", "制裁测试实体乙,Sanction Entity B", "YY"},
                    {"SANCTION", "SANDBOX-SANCTION-VESSEL", "制裁测试船舶", "ZZ"},
                    {"PEP", "SANDBOX-PEP-OFFICIAL-A", "政要测试甲,PEP Official A", "CN"},
                    {"PEP", "SANDBOX-PEP-OFFICIAL-B", "政要测试乙,PEP Official B", "US"},
            };
            for (String[] row : sandbox) {
                store.insertWatchlist(String.valueOf(store.nextId("seq_aml_watch")), row[0], row[1], row[2],
                        row[3], "system-seed");
            }
            refresh();
            log.info("R-6 沙箱名单 seed 完成 entries={}（离线数据，无外部请求）", cache.size());
        } catch (Exception e) {
            log.warn("R-6 沙箱名单 seed 失败（筛查降级为不命中，不阻断交易）: {}", e.getMessage());
        }
    }

    public void refresh() {
        try {
            List<Entry> next = new ArrayList<>();
            for (Map<String, Object> m : store.listWatchlist(null)) {
                if (!"ENABLED".equals(String.valueOf(m.get("status")))) {
                    continue;
                }
                List<String> aliases = new ArrayList<>();
                String raw = m.get("aliases") == null ? "" : String.valueOf(m.get("aliases"));
                for (String a : raw.split("[,|;]")) {
                    if (!a.isBlank()) {
                        aliases.add(norm(a));
                    }
                }
                next.add(new Entry(String.valueOf(m.get("id")), String.valueOf(m.get("listType")),
                        norm(String.valueOf(m.get("name"))), aliases,
                        m.get("country") == null ? "" : String.valueOf(m.get("country"))));
            }
            cache = List.copyOf(next);
            loadedAt = System.currentTimeMillis();
        } catch (Exception e) {
            log.warn("R-6 名单快照刷新失败（保留 last-known-good）: {}", e.getMessage());
        }
    }

    private List<Entry> entries() {
        if (System.currentTimeMillis() - loadedAt > TTL_MS) {
            refresh();
        }
        return cache;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    /** 筛查（listType 为空则两类都查，返回更严的 SANCTION 命中优先）。 */
    public Hit screen(String listType, String subject) {
        String s = norm(subject);
        if (s.isEmpty()) {
            return Hit.MISS;
        }
        Hit best = Hit.MISS;
        for (Entry e : entries()) {
            if (listType != null && !listType.isBlank() && !e.listType().equalsIgnoreCase(listType)) {
                continue;
            }
            String type = null;
            if (e.name().equals(s)) {
                type = "EXACT";
            } else if (e.aliases().contains(s)) {
                type = "ALIAS";
            } else if (e.name().length() >= 4 && s.length() >= 4
                    && (s.contains(e.name()) || e.name().contains(s))) {
                type = "CONTAINS";
            }
            if (type == null) {
                continue;
            }
            Hit hit = new Hit(true, e.id(), type, e.listType());
            if ("EXACT".equals(type) || "SANCTION".equals(e.listType())) {
                return hit;
            }
            best = hit;
        }
        return best;
    }

    /** 筛查留痕（命中与否都写）。 */
    public AmlScreeningDTO record(String subjectType, String subject, String listType, Hit hit, Long reportId,
                                  String traceId) {
        AmlScreeningDTO d = new AmlScreeningDTO();
        d.setId(store.nextId("seq_aml_screen"));
        d.setSubjectType(subjectType);
        d.setSubjectMask(mask(subject));
        d.setListType(listType == null || listType.isBlank() ? (hit.hit() ? hit.listType() : "ALL") : listType);
        d.setHit(hit.hit());
        d.setMatchedEntryId(hit.entryId());
        d.setMatchType(hit.matchType());
        d.setReportId(reportId);
        d.setTraceId(traceId);
        try {
            store.insertScreening(d);
        } catch (Exception e) {
            log.warn("R-6 筛查留痕写入失败（不阻断交易）: {}", e.getMessage());
        }
        return d;
    }

    public static String mask(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String v = s.trim();
        if (v.length() <= 8) {
            return v.substring(0, Math.min(2, v.length())) + "****";
        }
        return v.substring(0, 4) + "****" + v.substring(v.length() - 4);
    }

    public List<Map<String, Object>> list(String listType) {
        return store.listWatchlist(listType);
    }

    public Map<String, Object> add(String listType, String name, String aliases, String country, String operator) {
        String lt = listType == null ? "" : listType.trim().toUpperCase();
        if (!lt.equals("SANCTION") && !lt.equals("PEP")) {
            throw new IllegalArgumentException("listType 只支持 SANCTION/PEP");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 必填");
        }
        String id = String.valueOf(store.nextId("seq_aml_watch"));
        store.insertWatchlist(id, lt, name.trim(), aliases, country, operator);
        refresh();
        return store.listWatchlist(lt).stream()
                .filter(m -> name.trim().equals(String.valueOf(m.get("name"))))
                .findFirst().orElse(Map.of("id", id, "listType", lt, "name", name.trim()));
    }

    public Map<String, Object> remove(String entryId, String operator) {
        int n = store.removeWatchlist(entryId, operator);
        if (n == 0) {
            throw new IllegalArgumentException("名单条目不存在: " + entryId);
        }
        refresh();
        return Map.of("id", entryId, "status", "REMOVED", "operator", operator);
    }

    public long count() {
        return store.watchlistCount();
    }
}