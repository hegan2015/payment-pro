package com.qoder.payment.risk.aml;

import com.qoder.payment.facade.risk.AmlReportDTO;
import com.qoder.payment.facade.risk.AmlRuleDTO;
import com.qoder.payment.facade.risk.AmlScreeningDTO;
import com.qoder.payment.risk.store.RiskPlusSchemaInitializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R-6 AML 持久化（规则 / 可疑报告 STR / 交易流水镜像 / 报告引用 / 名单筛查 / 沙箱名单 / 状态审计）。
 *
 * <p>local H2（{@code data/risk}）+ docker MySQL（{@code pp_risk}）同一套 SQL。
 * <p><b>幂等</b>：报告唯一键 {@code (payer_id, rule_id, biz_date)}（DB 兜底），
 * 重复监测返回既有报告，不重复建单、不重复动账。
 * <p><b>数据保护</b>：只 INSERT / UPDATE 状态位；STR 与审计行永不 DELETE（留存期默认 5 年）。
 * <p><b>不阻断红线</b>：写入失败只 warn，绝不因 AML 自身持久化异常拒绝正常交易。
 */
@Slf4j
@Repository
public class AmlStore {

    private final JdbcTemplate jdbc;
    private final boolean mysql;

    public AmlStore(JdbcTemplate jdbc, RiskPlusSchemaInitializer initializer) {
        this.jdbc = jdbc;
        this.mysql = initializer.mysql();
        log.info("AmlStore 就绪 dialect={} schemaApplied={}", mysql ? "mysql" : "h2", initializer.applied());
    }

    public long nextId(String seq) {
        Long v = jdbc.queryForObject("SELECT next_value_for('" + seq + "')", Long.class);
        return v == null ? System.nanoTime() : v;
    }

    // ---------------- 规则 ----------------

    private static final RowMapper<AmlRuleDTO> RULE = (rs, i) -> {
        AmlRuleDTO d = new AmlRuleDTO();
        d.setId(rs.getString("id"));
        d.setName(rs.getString("name"));
        d.setRuleType(rs.getString("rule_type"));
        d.setConditionExpr(rs.getString("condition_expr"));
        long th = rs.getLong("threshold");
        d.setThreshold(rs.wasNull() ? null : th);
        long wm = rs.getLong("window_millis");
        d.setWindowMillis(rs.wasNull() ? null : wm);
        int mc = rs.getInt("min_count");
        d.setMinCount(rs.wasNull() ? null : mc);
        int ra = rs.getInt("ratio");
        d.setRatio(rs.wasNull() ? null : ra);
        d.setScore(rs.getInt("score"));
        d.setHardRule(rs.getBoolean("hard_rule"));
        d.setRealtime(rs.getBoolean("realtime"));
        d.setStatus(rs.getString("status"));
        d.setVersion(rs.getInt("version"));
        d.setOperator(rs.getString("operator"));
        d.setCompileStatus(rs.getString("compile_status"));
        d.setCompileError(rs.getString("compile_error"));
        d.setCreatedAt(rs.getLong("created_at"));
        d.setUpdatedAt(rs.getLong("updated_at"));
        return d;
    };

    public List<AmlRuleDTO> listRules() {
        return jdbc.query("SELECT * FROM aml_rule ORDER BY id ASC", RULE);
    }

    public AmlRuleDTO getRule(String id) {
        List<AmlRuleDTO> list = jdbc.query("SELECT * FROM aml_rule WHERE id=?", RULE, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public long ruleCount() {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM aml_rule", Long.class);
        return v == null ? 0 : v;
    }

    /** 规则 upsert（只 INSERT/UPDATE，历史版本经 version 递增留痕）。 */
    public void saveRule(AmlRuleDTO d) {
        long now = System.currentTimeMillis();
        int updated = jdbc.update("UPDATE aml_rule SET name=?, rule_type=?, condition_expr=?, threshold=?,"
                        + " window_millis=?, min_count=?, ratio=?, score=?, hard_rule=?, realtime=?, status=?,"
                        + " version=?, operator=?, compile_status=?, compile_error=?, updated_at=? WHERE id=?",
                d.getName(), d.getRuleType(), d.getConditionExpr(), d.getThreshold(), d.getWindowMillis(),
                d.getMinCount(), d.getRatio(), nz(d.getScore()), bool(d.getHardRule()), bool(d.getRealtime()),
                d.getStatus(), nz(d.getVersion()), d.getOperator(), d.getCompileStatus(), d.getCompileError(),
                now, d.getId());
        if (updated > 0) {
            return;
        }
        jdbc.update("INSERT INTO aml_rule(id,name,rule_type,condition_expr,threshold,window_millis,min_count,"
                        + "ratio,score,hard_rule,realtime,status,version,operator,compile_status,compile_error,"
                        + "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                d.getId(), d.getName(), d.getRuleType(), d.getConditionExpr(), d.getThreshold(), d.getWindowMillis(),
                d.getMinCount(), d.getRatio(), nz(d.getScore()), bool(d.getHardRule()), bool(d.getRealtime()),
                d.getStatus(), nz(d.getVersion()), d.getOperator(), d.getCompileStatus(), d.getCompileError(),
                now, now);
    }

    // ---------------- 报告 ----------------

    private static final RowMapper<AmlReportDTO> REPORT = (rs, i) -> {
        AmlReportDTO d = new AmlReportDTO();
        d.setId(Long.parseLong(rs.getString("id")));
        d.setReportNo(rs.getString("report_no"));
        d.setPayerId(rs.getString("payer_id"));
        d.setMerchantId(rs.getString("merchant_id"));
        d.setRuleId(rs.getString("rule_id"));
        d.setRuleType(rs.getString("rule_type"));
        d.setTxnAmount(rs.getLong("txn_amount"));
        d.setTxnCount(rs.getInt("txn_count"));
        d.setBizDate(rs.getString("biz_date"));
        d.setStatus(rs.getString("status"));
        d.setScore(rs.getInt("score"));
        d.setDisposition(rs.getString("disposition"));
        d.setHardRule(rs.getBoolean("hard_rule"));
        d.setDetail(rs.getString("detail"));
        long dsp = rs.getLong("dispute_id");
        d.setDisputeId(rs.wasNull() ? null : dsp);
        d.setOperator(rs.getString("operator"));
        d.setSubmitNote(rs.getString("submit_note"));
        long sa = rs.getLong("submitted_at");
        d.setSubmittedAt(rs.wasNull() ? null : sa);
        long ca = rs.getLong("closed_at");
        d.setClosedAt(rs.wasNull() ? null : ca);
        long ru = rs.getLong("retention_until");
        d.setRetentionUntil(rs.wasNull() ? null : ru);
        d.setCreatedAt(rs.getLong("created_at"));
        d.setUpdatedAt(rs.getLong("updated_at"));
        return d;
    };

    public AmlReportDTO findReport(String payerId, String ruleId, String bizDate) {
        List<AmlReportDTO> list = jdbc.query("SELECT * FROM aml_report WHERE payer_id=? AND rule_id=? AND biz_date=?",
                REPORT, payerId, ruleId, bizDate);
        return list.isEmpty() ? null : list.get(0);
    }

    public AmlReportDTO getReport(long id) {
        List<AmlReportDTO> list = jdbc.query("SELECT * FROM aml_report WHERE id=?", REPORT, String.valueOf(id));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 插入报告；唯一键冲突返回 false（幂等复用既有报告）。 */
    public boolean insertReport(AmlReportDTO d) {
        long now = System.currentTimeMillis();
        try {
            jdbc.update("INSERT INTO aml_report(id,report_no,payer_id,merchant_id,rule_id,rule_type,txn_amount,"
                            + "txn_count,biz_date,status,score,disposition,hard_rule,detail,dispute_id,operator,"
                            + "submit_note,submitted_at,closed_at,retention_until,created_at,updated_at)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    String.valueOf(d.getId()), d.getReportNo(), d.getPayerId(), d.getMerchantId(), d.getRuleId(),
                    d.getRuleType(), d.getTxnAmount(), nz(d.getTxnCount()), d.getBizDate(), d.getStatus(),
                    nz(d.getScore()), d.getDisposition(), bool(d.getHardRule()), d.getDetail(), d.getDisputeId(),
                    d.getOperator(), d.getSubmitNote(), d.getSubmittedAt(), d.getClosedAt(), d.getRetentionUntil(),
                    now, now);
            return true;
        } catch (Exception e) {
            log.info("AML 报告幂等命中（复用既有报告）payer={} rule={} bizDate={}: {}",
                    d.getPayerId(), d.getRuleId(), d.getBizDate(), e.getMessage());
            return false;
        }
    }

    public List<AmlReportDTO> listReports(String status, String bizDate, String payerId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM aml_report WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            args.add(status.trim().toUpperCase());
        }
        if (bizDate != null && !bizDate.isBlank()) {
            sql.append(" AND biz_date=?");
            args.add(bizDate.trim());
        }
        if (payerId != null && !payerId.isBlank()) {
            sql.append(" AND payer_id=?");
            args.add(payerId.trim());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ").append(limit);
        return jdbc.query(sql.toString(), REPORT, args.toArray());
    }

    /** 状态 CAS 流转；返回受影响行数（0=状态已变，调用方判冲突）。 */
    public int casStatus(long id, String from, String to, String operator, String note, boolean submit) {
        long now = System.currentTimeMillis();
        if (submit) {
            return jdbc.update("UPDATE aml_report SET status=?, operator=?, submit_note=?, submitted_at=?,"
                    + " updated_at=? WHERE id=? AND status=?", to, operator, note, now, now, String.valueOf(id), from);
        }
        return jdbc.update("UPDATE aml_report SET status=?, operator=?, submit_note=?, closed_at=?,"
                + " updated_at=? WHERE id=? AND status=?", to, operator, note, now, now, String.valueOf(id), from);
    }

    public void attachDispute(long reportId, long disputeId) {
        jdbc.update("UPDATE aml_report SET dispute_id=?, updated_at=? WHERE id=?",
                disputeId, System.currentTimeMillis(), String.valueOf(reportId));
    }

    public long reportCount() {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM aml_report", Long.class);
        return v == null ? 0 : v;
    }

    public long reportCountByStatus(String status) {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM aml_report WHERE status=?", Long.class, status);
        return v == null ? 0 : v;
    }

    // ---------------- 审计 ----------------

    public void insertAudit(long reportId, String action, String from, String to, String operator, String note) {
        jdbc.update("INSERT INTO aml_audit(id,report_id,action,from_status,to_status,operator,note,created_at)"
                        + " VALUES(?,?,?,?,?,?,?,?)",
                String.valueOf(nextId("seq_aml_audit")), String.valueOf(reportId), action, from, to, operator,
                note == null ? null : note.substring(0, Math.min(note.length(), 500)), System.currentTimeMillis());
    }

    public List<Map<String, Object>> listAudit(long reportId) {
        return jdbc.query("SELECT id,report_id,action,from_status,to_status,operator,note,created_at FROM aml_audit"
                + " WHERE report_id=? ORDER BY created_at ASC, id ASC", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("reportId", rs.getLong("report_id"));
            m.put("action", rs.getString("action"));
            m.put("fromStatus", rs.getString("from_status"));
            m.put("toStatus", rs.getString("to_status"));
            m.put("operator", rs.getString("operator"));
            m.put("note", rs.getString("note"));
            m.put("createdAt", rs.getLong("created_at"));
            return m;
        }, String.valueOf(reportId));
    }

    // ---------------- 交易流水镜像 ----------------

    public void insertTxn(long id, String payerId, String merchantId, String paymentId, long amount,
                          String direction, String scene, String method, String ip, String region, String country,
                          String deviceKey, int hour, String bizDate, String traceId, long now) {
        jdbc.update("INSERT INTO aml_txn(id,payer_id,merchant_id,payment_id,amount,direction,scene,method,ip,"
                        + "region,country,device_id,hour_of_day,biz_date,trace_id,created_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                String.valueOf(id), payerId, merchantId, paymentId, amount, direction, scene, method, ip, region,
                country, deviceKey, hour, bizDate, traceId, now);
    }

    private static final RowMapper<Map<String, Object>> TXN = (rs, i) -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("payerId", rs.getString("payer_id"));
        m.put("merchantId", rs.getString("merchant_id"));
        m.put("paymentId", rs.getString("payment_id"));
        m.put("amount", rs.getLong("amount"));
        m.put("direction", rs.getString("direction"));
        m.put("scene", rs.getString("scene"));
        m.put("method", rs.getString("method"));
        m.put("ip", rs.getString("ip"));
        m.put("region", rs.getString("region"));
        m.put("country", rs.getString("country"));
        m.put("hourOfDay", rs.getInt("hour_of_day"));
        m.put("bizDate", rs.getString("biz_date"));
        m.put("createdAt", rs.getLong("created_at"));
        return m;
    };

    public List<Map<String, Object>> listTxns(String payerId, String bizDate, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM aml_txn WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (payerId != null && !payerId.isBlank()) {
            sql.append(" AND payer_id=?");
            args.add(payerId);
        }
        if (bizDate != null && !bizDate.isBlank()) {
            sql.append(" AND biz_date=?");
            args.add(bizDate);
        }
        sql.append(" ORDER BY created_at ASC, id ASC LIMIT ").append(limit);
        return jdbc.query(sql.toString(), TXN, args.toArray());
    }

    public List<String> distinctPayers(String bizDate) {
        return jdbc.query("SELECT DISTINCT payer_id FROM aml_txn WHERE biz_date=? ORDER BY payer_id LIMIT 2000",
                (rs, i) -> rs.getString(1), bizDate);
    }

    public long txnCount() {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM aml_txn", Long.class);
        return v == null ? 0 : v;
    }

    /** 当日累计金额/笔数（QLExpress dayAmount/dayCount 函数与报告口径共用）。 */
    public long[] daySum(String payerId, String bizDate) {
        long[] v = jdbc.query("SELECT COALESCE(SUM(amount),0), COUNT(*) FROM aml_txn WHERE payer_id=? AND biz_date=?",
                rs -> {
                    rs.next();
                    return new long[]{rs.getLong(1), rs.getLong(2)};
                }, payerId, bizDate);
        return v == null ? new long[]{0, 0} : v;
    }

    public long txnCountByDate(String bizDate) {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM aml_txn WHERE biz_date=?", Long.class, bizDate);
        return v == null ? 0 : v;
    }

    // ---------------- 报告-交易引用 ----------------

    public void insertRef(long reportId, String paymentId, long amount, String direction) {
        try {
            jdbc.update("INSERT INTO aml_txn_ref(id,report_id,payment_id,amount,direction,created_at)"
                            + " VALUES(?,?,?,?,?,?)",
                    String.valueOf(nextId("seq_aml_ref")), String.valueOf(reportId), paymentId, amount, direction,
                    System.currentTimeMillis());
        } catch (Exception e) {
            // 唯一键冲突=已引用，幂等忽略
            log.debug("AML 报告交易引用幂等忽略 reportId={} paymentId={}", reportId, paymentId);
        }
    }

    public List<String> listRefs(long reportId) {
        return jdbc.query("SELECT payment_id FROM aml_txn_ref WHERE report_id=? ORDER BY id ASC",
                (rs, i) -> rs.getString(1), String.valueOf(reportId));
    }

    // ---------------- 名单筛查 ----------------

    public void insertScreening(AmlScreeningDTO d) {
        jdbc.update("INSERT INTO aml_screening(id,subject_type,subject_mask,list_type,hit,matched_entry_id,"
                        + "match_type,report_id,trace_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                String.valueOf(d.getId()), d.getSubjectType(), d.getSubjectMask(), d.getListType(),
                bool(d.getHit()), d.getMatchedEntryId(), d.getMatchType(), d.getReportId(), d.getTraceId(),
                System.currentTimeMillis());
    }

    private static final RowMapper<AmlScreeningDTO> SCREEN = (rs, i) -> {
        AmlScreeningDTO d = new AmlScreeningDTO();
        d.setId(Long.parseLong(rs.getString("id")));
        d.setSubjectType(rs.getString("subject_type"));
        d.setSubjectMask(rs.getString("subject_mask"));
        d.setListType(rs.getString("list_type"));
        d.setHit(rs.getBoolean("hit"));
        d.setMatchedEntryId(rs.getString("matched_entry_id"));
        d.setMatchType(rs.getString("match_type"));
        long rid = rs.getLong("report_id");
        d.setReportId(rs.wasNull() ? null : rid);
        d.setTraceId(rs.getString("trace_id"));
        d.setCreatedAt(rs.getLong("created_at"));
        return d;
    };

    public List<AmlScreeningDTO> listScreenings(String subjectMask, int limit) {
        if (subjectMask == null || subjectMask.isBlank()) {
            return jdbc.query("SELECT * FROM aml_screening ORDER BY created_at DESC, id DESC LIMIT " + limit, SCREEN);
        }
        return jdbc.query("SELECT * FROM aml_screening WHERE subject_mask=?"
                + " ORDER BY created_at DESC, id DESC LIMIT " + limit, SCREEN, subjectMask);
    }

    public long screeningCount() {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM aml_screening", Long.class);
        return v == null ? 0 : v;
    }

    // ---------------- 沙箱名单 ----------------

    public List<Map<String, Object>> listWatchlist(String listType) {
        String sql = "SELECT id,list_type,name,aliases,country,status,source,operator,created_at,updated_at"
                + " FROM aml_watchlist";
        List<Object> args = new ArrayList<>();
        if (listType != null && !listType.isBlank()) {
            sql += " WHERE list_type=?";
            args.add(listType.trim().toUpperCase());
        }
        sql += " ORDER BY list_type ASC, id ASC LIMIT 500";
        return jdbc.query(sql, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("listType", rs.getString("list_type"));
            m.put("name", rs.getString("name"));
            m.put("aliases", rs.getString("aliases"));
            m.put("country", rs.getString("country"));
            m.put("status", rs.getString("status"));
            m.put("source", rs.getString("source"));
            m.put("operator", rs.getString("operator"));
            m.put("createdAt", rs.getLong("created_at"));
            m.put("updatedAt", rs.getLong("updated_at"));
            return m;
        }, args.toArray());
    }

    public boolean insertWatchlist(String id, String listType, String name, String aliases, String country,
                                   String operator) {
        long now = System.currentTimeMillis();
        try {
            jdbc.update("INSERT INTO aml_watchlist(id,list_type,name,aliases,country,status,source,operator,"
                            + "created_at,updated_at) VALUES(?,?,?,?,?,'ENABLED','SANDBOX',?,?,?)",
                    id, listType, name, aliases, country, operator, now, now);
            return true;
        } catch (Exception e) {
            int n = jdbc.update("UPDATE aml_watchlist SET aliases=?, country=?, status='ENABLED', operator=?,"
                    + " updated_at=? WHERE list_type=? AND name=?", aliases, country, operator, now, listType, name);
            return n > 0;
        }
    }

    public int removeWatchlist(String id, String operator) {
        return jdbc.update("UPDATE aml_watchlist SET status='REMOVED', operator=?, updated_at=? WHERE id=?",
                operator, System.currentTimeMillis(), id);
    }

    public long watchlistCount() {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM aml_watchlist WHERE status='ENABLED'", Long.class);
        return v == null ? 0 : v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static boolean bool(Boolean v) {
        return Boolean.TRUE.equals(v);
    }
}