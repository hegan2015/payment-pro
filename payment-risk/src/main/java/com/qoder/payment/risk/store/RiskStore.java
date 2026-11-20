package com.qoder.payment.risk.store;

import com.qoder.payment.facade.risk.RiskDecisionDTO;
import com.qoder.payment.facade.risk.RiskSnapshotDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风控持久化（R-1 规则集版本 / R-4 处置留痕 / §5.4 护栏告警 / R-4 人审 / R-2 用量镜像）。
 *
 * <p>local H2（{@code data/risk}）+ docker MySQL（{@code pp_risk}）同一套 SQL：
 * 取号统一 {@code SELECT next_value_for('seq_x')}（H2 ALIAS / MySQL 存储函数）。
 * <p><b>数据保护</b>：只 INSERT / UPDATE 状态位，历史版本与留痕行永不 DELETE。
 * <p><b>不阻断红线</b>：留痕/告警写入失败只 warn，绝不因风控自身持久化异常拒绝正常交易。
 */
@Slf4j
@Repository
public class RiskStore {

    private final JdbcTemplate jdbc;
    private final boolean mysql;

    public RiskStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.mysql = detectMysql(jdbc);
        log.info("RiskStore 就绪 dialect={}", mysql ? "mysql" : "h2");
    }

    private static boolean detectMysql(JdbcTemplate jdbc) {
        try {
            return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                    c -> c.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql"));
        } catch (Exception e) {
            log.warn("风控库方言探测失败，按 H2 处理: {}", e.getMessage());
            return false;
        }
    }

    public boolean mysql() {
        return mysql;
    }

    public long nextId(String seq) {
        Long v = jdbc.queryForObject("SELECT next_value_for('" + seq + "')", Long.class);
        return v == null ? System.nanoTime() : v;
    }

    // ---------------- 配置快照（版本化 + 审计 + 回滚） ----------------

    private static final RowMapper<RiskSnapshotDTO> SNAPSHOT_MAPPER = (rs, i) -> {
        RiskSnapshotDTO d = new RiskSnapshotDTO();
        d.setId(Long.parseLong(rs.getString("id")));
        d.setKind(rs.getString("kind"));
        d.setVersion(rs.getInt("version"));
        d.setPayload(rs.getString("payload"));
        d.setSource(rs.getString("source"));
        d.setStatus(rs.getString("status"));
        d.setOperator(rs.getString("operator"));
        d.setChangeNote(rs.getString("change_note"));
        d.setCreatedAt(rs.getLong("created_at"));
        return d;
    };

    public RiskSnapshotDTO activeSnapshot(String kind) {
        List<RiskSnapshotDTO> list = jdbc.query(
                "SELECT * FROM risk_config_snapshot WHERE kind=? AND status='ACTIVE' ORDER BY version DESC",
                SNAPSHOT_MAPPER, kind);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<RiskSnapshotDTO> versions(String kind, int limit) {
        return jdbc.query("SELECT * FROM risk_config_snapshot WHERE kind=? ORDER BY version DESC LIMIT " + limit,
                SNAPSHOT_MAPPER, kind);
    }

    public int maxVersion(String kind) {
        Integer v = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version),0) FROM risk_config_snapshot WHERE kind=?", Integer.class, kind);
        return v == null ? 0 : v;
    }

    /** 取上一版（version < activeVersion 的最大版本，含已回滚/被替代的历史行）。 */
    public RiskSnapshotDTO previousVersion(String kind, int activeVersion) {
        List<RiskSnapshotDTO> list = jdbc.query(
                "SELECT * FROM risk_config_snapshot WHERE kind=? AND version<? ORDER BY version DESC LIMIT 1",
                SNAPSHOT_MAPPER, kind, activeVersion);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 新增 ACTIVE 版本并把同 kind 其它 ACTIVE 置为 SUPERSEDED（历史行保留）。 */
    public RiskSnapshotDTO newVersion(String kind, String payload, String source, String operator, String note) {
        int version = maxVersion(kind) + 1;
        long id = nextId("seq_risk_snapshot");
        jdbc.update("UPDATE risk_config_snapshot SET status='SUPERSEDED' WHERE kind=? AND status='ACTIVE'", kind);
        jdbc.update("INSERT INTO risk_config_snapshot(id,kind,version,payload,source,status,operator,change_note,created_at)"
                        + " VALUES(?,?,?,?,?,'ACTIVE',?,?,?)",
                String.valueOf(id), kind, version, payload, source, operator, note, System.currentTimeMillis());
        RiskSnapshotDTO d = new RiskSnapshotDTO();
        d.setId(id);
        d.setKind(kind);
        d.setVersion(version);
        d.setPayload(payload);
        d.setSource(source);
        d.setStatus("ACTIVE");
        d.setOperator(operator);
        d.setChangeNote(note);
        d.setCreatedAt(System.currentTimeMillis());
        return d;
    }

    // ---------------- 处置留痕 ----------------

    private static final RowMapper<RiskDecisionDTO> DECISION_MAPPER = (rs, i) -> {
        RiskDecisionDTO d = new RiskDecisionDTO();
        d.setId(Long.parseLong(rs.getString("id")));
        d.setPayerId(rs.getString("payer_id"));
        d.setMerchantId(rs.getString("merchant_id"));
        d.setAmount(rs.getLong("amount"));
        d.setScene(rs.getString("scene"));
        d.setMethod(rs.getString("method"));
        d.setChannelCode(rs.getString("channel_code"));
        d.setCardMask(rs.getString("card_mask"));
        d.setDeviceId(rs.getString("device_id"));
        d.setIp(rs.getString("ip"));
        d.setScore(rs.getInt("score"));
        d.setAction(rs.getString("action"));
        d.setPass(rs.getBoolean("passed"));
        d.setReason(rs.getString("reason"));
        d.setCode(rs.getString("code"));
        d.setHitRules(rs.getString("hit_rules"));
        d.setFactors(rs.getString("factors"));
        d.setShadow(rs.getBoolean("shadow"));
        d.setDegraded(rs.getBoolean("degraded"));
        d.setRuleVersion(rs.getInt("rule_version"));
        d.setListHit(rs.getString("list_hit"));
        d.setQuotaHit(rs.getString("quota_hit"));
        d.setTraceId(rs.getString("trace_id"));
        d.setBizNo(rs.getString("biz_no"));
        long dispute = rs.getLong("dispute_id");
        d.setDisputeId(rs.wasNull() ? null : dispute);
        d.setCreatedAt(rs.getLong("created_at"));
        return d;
    };

    /** 落留痕；失败只 warn 返回 null（绝不阻断支付）。 */
    public Long insertDecision(RiskDecisionDTO d) {
        try {
            long id = nextId("seq_risk_decision");
            jdbc.update("INSERT INTO risk_decision_log(id,payer_id,merchant_id,amount,scene,method,channel_code,"
                            + "card_mask,device_id,ip,score,action,passed,reason,code,hit_rules,factors,shadow,degraded,"
                            + "rule_version,list_hit,quota_hit,trace_id,biz_no,dispute_id,created_at)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    String.valueOf(id), d.getPayerId(), d.getMerchantId(), d.getAmount(), d.getScene(), d.getMethod(),
                    d.getChannelCode(), d.getCardMask(), d.getDeviceId(), d.getIp(), d.getScore(), d.getAction(),
                    Boolean.TRUE.equals(d.getPass()), d.getReason(), d.getCode(), d.getHitRules(), d.getFactors(),
                    Boolean.TRUE.equals(d.getShadow()), Boolean.TRUE.equals(d.getDegraded()), d.getRuleVersion(),
                    d.getListHit(), d.getQuotaHit(), d.getTraceId(), d.getBizNo(), d.getDisputeId(),
                    System.currentTimeMillis());
            return id;
        } catch (Exception e) {
            log.warn("风控留痕写入失败（不阻断交易）: {}", e.getMessage());
            return null;
        }
    }

    public List<RiskDecisionDTO> listDecisions(String payerId, int limit) {
        if (payerId == null || payerId.isBlank()) {
            return jdbc.query("SELECT * FROM risk_decision_log ORDER BY created_at DESC, id DESC LIMIT " + limit,
                    DECISION_MAPPER);
        }
        return jdbc.query("SELECT * FROM risk_decision_log WHERE payer_id=? ORDER BY created_at DESC, id DESC LIMIT "
                + limit, DECISION_MAPPER, payerId);
    }

    public RiskDecisionDTO getDecision(long id) {
        List<RiskDecisionDTO> list = jdbc.query("SELECT * FROM risk_decision_log WHERE id=?",
                DECISION_MAPPER, String.valueOf(id));
        if (list.isEmpty()) {
            return null;
        }
        RiskDecisionDTO d = list.get(0);
        Map<String, Object> review = reviewByDecision(id);
        if (review != null) {
            d.setReviewStatus(String.valueOf(review.get("status")));
        }
        return d;
    }

    public void attachDispute(long decisionId, long disputeId) {
        try {
            jdbc.update("UPDATE risk_decision_log SET dispute_id=? WHERE id=?", disputeId, String.valueOf(decisionId));
        } catch (Exception e) {
            log.warn("风控留痕关联差错单失败: {}", e.getMessage());
        }
    }

    // ---------------- 护栏告警 ----------------

    public void alert(String kind, String ruleId, String message, String traceId) {
        try {
            jdbc.update("INSERT INTO risk_alert(id,kind,rule_id,message,trace_id,created_at) VALUES(?,?,?,?,?,?)",
                    String.valueOf(nextId("seq_risk_alert")), kind, ruleId,
                    message == null ? null : message.substring(0, Math.min(message.length(), 1000)),
                    traceId, System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("风控告警写入失败: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> listAlerts(int limit) {
        return jdbc.query("SELECT id,kind,rule_id,message,trace_id,created_at FROM risk_alert"
                + " ORDER BY created_at DESC, id DESC LIMIT " + limit, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("kind", rs.getString("kind"));
            m.put("ruleId", rs.getString("rule_id"));
            m.put("message", rs.getString("message"));
            m.put("traceId", rs.getString("trace_id"));
            m.put("createdAt", rs.getLong("created_at"));
            return m;
        });
    }

    public long alertCount() {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM risk_alert", Long.class);
        return v == null ? 0 : v;
    }

    // ---------------- 人审 ----------------

    public void insertReview(long decisionId, String payerId, Long amount, Long disputeId) {
        try {
            long now = System.currentTimeMillis();
            jdbc.update("INSERT INTO risk_review(id,decision_id,payer_id,amount,status,reviewer,dispute_id,"
                            + "created_at,updated_at) VALUES(?,?,?,?,'PENDING',NULL,?,?,?)",
                    String.valueOf(nextId("seq_risk_review")), decisionId, payerId, amount, disputeId, now, now);
        } catch (Exception e) {
            log.warn("人审工单写入失败: {}", e.getMessage());
        }
    }

    public Map<String, Object> reviewByDecision(long decisionId) {
        List<Map<String, Object>> list = jdbc.query(
                "SELECT id,decision_id,status,reviewer,dispute_id,created_at,updated_at FROM risk_review"
                        + " WHERE decision_id=? ORDER BY created_at DESC LIMIT 1", (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("decisionId", rs.getLong("decision_id"));
                    m.put("status", rs.getString("status"));
                    m.put("reviewer", rs.getString("reviewer"));
                    long did = rs.getLong("dispute_id");
                    m.put("disputeId", rs.wasNull() ? null : did);
                    m.put("createdAt", rs.getLong("created_at"));
                    m.put("updatedAt", rs.getLong("updated_at"));
                    return m;
                }, decisionId);
        return list.isEmpty() ? null : list.get(0);
    }

    public void finishReview(long decisionId, String status, String reviewer) {
        jdbc.update("UPDATE risk_review SET status=?, reviewer=?, updated_at=? WHERE decision_id=? AND status='PENDING'",
                status, reviewer, System.currentTimeMillis(), decisionId);
    }

    // ---------------- R-2 用量镜像（仅在配置了限额规则时写入） ----------------

    public void upsertUsage(String quotaKey, String periodKey, long amount, int count) {
        try {
            long now = System.currentTimeMillis();
            if (mysql) {
                jdbc.update("INSERT INTO quota_usage(quota_key,period_key,used_amount,used_count,updated_at)"
                                + " VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE used_amount=?, used_count=?, updated_at=?",
                        quotaKey, periodKey, amount, count, now, amount, count, now);
            } else {
                jdbc.update("MERGE INTO quota_usage(quota_key,period_key,used_amount,used_count,updated_at)"
                        + " KEY(quota_key,period_key) VALUES(?,?,?,?,?)", quotaKey, periodKey, amount, count, now);
            }
        } catch (Exception e) {
            log.warn("限额用量镜像写入失败（计数权威在内存/Redis，不影响限额判定）: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> listUsage(String keyPrefix) {
        String like = (keyPrefix == null || keyPrefix.isBlank() ? "" : keyPrefix) + "%";
        List<Map<String, Object>> out = new ArrayList<>(jdbc.query(
                "SELECT quota_key,period_key,used_amount,used_count,updated_at FROM quota_usage"
                        + " WHERE quota_key LIKE ? ORDER BY updated_at DESC LIMIT 200", (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("quotaKey", rs.getString("quota_key"));
                    m.put("periodKey", rs.getString("period_key"));
                    m.put("usedAmount", rs.getLong("used_amount"));
                    m.put("usedCount", rs.getInt("used_count"));
                    m.put("updatedAt", rs.getLong("updated_at"));
                    return m;
                }, like));
        return out;
    }
}