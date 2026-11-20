package com.qoder.payment.risk.device;

import com.qoder.payment.facade.risk.BehaviorEventDTO;
import com.qoder.payment.facade.risk.DeviceAccountDTO;
import com.qoder.payment.facade.risk.DeviceProfileDTO;
import com.qoder.payment.facade.risk.IpProfileDTO;
import com.qoder.payment.risk.store.RiskPlusSchemaInitializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * R-5 画像持久化（设备指纹 / 设备-账户图谱 / IP 画像 / 行为序列）。
 *
 * <p>local H2（{@code data/risk}）+ docker MySQL（{@code pp_risk}）同一套 SQL，
 * 取号统一 {@code SELECT next_value_for('seq_x')}。
 * <p><b>数据保护</b>：只 INSERT / UPDATE；留存期到期<b>匿名化</b>（清标签/指纹/掩码 + expired=true），
 * 历史行永不 DELETE。
 * <p><b>不阻断红线</b>：所有写入失败只 warn，绝不因画像自身持久化异常拒绝正常交易。
 */
@Slf4j
@Repository
public class DeviceStore {

    private final JdbcTemplate jdbc;
    private final boolean mysql;

    public DeviceStore(JdbcTemplate jdbc, RiskPlusSchemaInitializer initializer) {
        this.jdbc = jdbc;
        this.mysql = initializer.mysql();
        log.info("DeviceStore 就绪 dialect={} schemaApplied={}", mysql ? "mysql" : "h2", initializer.applied());
    }

    public long nextId(String seq) {
        Long v = jdbc.queryForObject("SELECT next_value_for('" + seq + "')", Long.class);
        return v == null ? System.nanoTime() : v;
    }

    // ---------------- 设备指纹 ----------------

    private static final RowMapper<DeviceProfileDTO> DEVICE = (rs, i) -> {
        DeviceProfileDTO d = new DeviceProfileDTO();
        d.setDeviceId(rs.getString("device_id"));
        d.setDeviceMask(rs.getString("device_mask"));
        d.setFingerprintHash(rs.getString("fingerprint_hash"));
        d.setStability(rs.getString("stability"));
        d.setDriftCount(rs.getInt("drift_count"));
        d.setFirstSeen(rs.getLong("first_seen"));
        d.setLastSeen(rs.getLong("last_seen"));
        d.setAccountCount(rs.getInt("account_count"));
        d.setRiskScore(rs.getInt("risk_score"));
        d.setTags(rs.getString("tags"));
        d.setDetail(rs.getString("detail"));
        long ru = rs.getLong("retention_until");
        d.setRetentionUntil(rs.wasNull() ? null : ru);
        d.setExpired(rs.getBoolean("expired"));
        d.setCreatedAt(rs.getLong("created_at"));
        d.setUpdatedAt(rs.getLong("updated_at"));
        d.setRisky(d.getRiskScore() != null && d.getRiskScore() > 0);
        return d;
    };

    public DeviceProfileDTO getDevice(String key) {
        List<DeviceProfileDTO> list = jdbc.query("SELECT * FROM device_fingerprint WHERE device_id=?",
                DEVICE, key);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 新增或更新设备画像（先 UPDATE，0 行再 INSERT；并发重复键回退 UPDATE）。 */
    public void saveDevice(DeviceProfileDTO d) {
        long now = System.currentTimeMillis();
        int updated = jdbc.update("UPDATE device_fingerprint SET device_mask=?, fingerprint_hash=?, stability=?,"
                        + " drift_count=?, last_seen=?, account_count=?, risk_score=?, tags=?, detail=?,"
                        + " retention_until=?, updated_at=? WHERE device_id=?",
                d.getDeviceMask(), d.getFingerprintHash(), d.getStability(), nz(d.getDriftCount()),
                d.getLastSeen() == null ? now : d.getLastSeen(), nz(d.getAccountCount()), nz(d.getRiskScore()),
                d.getTags(), d.getDetail(), d.getRetentionUntil(), now, d.getDeviceId());
        if (updated > 0) {
            return;
        }
        try {
            jdbc.update("INSERT INTO device_fingerprint(device_id,device_mask,fingerprint_hash,stability,"
                            + "drift_count,first_seen,last_seen,account_count,risk_score,tags,detail,"
                            + "retention_until,expired,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    d.getDeviceId(), d.getDeviceMask(), d.getFingerprintHash(), d.getStability(),
                    nz(d.getDriftCount()), d.getFirstSeen() == null ? now : d.getFirstSeen(),
                    d.getLastSeen() == null ? now : d.getLastSeen(), nz(d.getAccountCount()), nz(d.getRiskScore()),
                    d.getTags(), d.getDetail(), d.getRetentionUntil(), false, now, now);
        } catch (Exception e) {
            log.warn("设备画像插入冲突（回退更新）key={}: {}", d.getDeviceId(), e.getMessage());
            jdbc.update("UPDATE device_fingerprint SET last_seen=?, account_count=?, risk_score=?, tags=?,"
                            + " detail=?, updated_at=? WHERE device_id=?",
                    now, nz(d.getAccountCount()), nz(d.getRiskScore()), d.getTags(), d.getDetail(), now,
                    d.getDeviceId());
        }
    }

    public long deviceCount() {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM device_fingerprint", Long.class);
        return v == null ? 0 : v;
    }

    // ---------------- 设备-账户图谱 ----------------

    private static final RowMapper<DeviceAccountDTO> REL = (rs, i) -> {
        DeviceAccountDTO d = new DeviceAccountDTO();
        d.setId(Long.parseLong(rs.getString("id")));
        d.setDeviceId(rs.getString("device_id"));
        d.setAccountId(rs.getString("account_id"));
        d.setFirstSeen(rs.getLong("first_seen"));
        d.setLastSeen(rs.getLong("last_seen"));
        d.setTxnCount(rs.getInt("txn_count"));
        return d;
    };

    /** 关联边 upsert；返回 true=新建边（用于 account_count 递增）。 */
    public boolean touchRel(String deviceKey, String accountId) {
        long now = System.currentTimeMillis();
        int updated = jdbc.update("UPDATE device_account_rel SET last_seen=?, txn_count=txn_count+1"
                + " WHERE device_id=? AND account_id=?", now, deviceKey, accountId);
        if (updated > 0) {
            return false;
        }
        try {
            jdbc.update("INSERT INTO device_account_rel(id,device_id,account_id,first_seen,last_seen,txn_count)"
                    + " VALUES(?,?,?,?,?,1)", String.valueOf(nextId("seq_device_rel")), deviceKey, accountId, now, now);
            return true;
        } catch (Exception e) {
            jdbc.update("UPDATE device_account_rel SET last_seen=?, txn_count=txn_count+1"
                    + " WHERE device_id=? AND account_id=?", now, deviceKey, accountId);
            return false;
        }
    }

    public int countAccounts(String deviceKey) {
        Integer v = jdbc.queryForObject("SELECT COUNT(*) FROM device_account_rel WHERE device_id=?",
                Integer.class, deviceKey);
        return v == null ? 0 : v;
    }

    public List<DeviceAccountDTO> listDeviceAccounts(String deviceKey) {
        return jdbc.query("SELECT * FROM device_account_rel WHERE device_id=? ORDER BY last_seen DESC LIMIT 200",
                REL, deviceKey);
    }

    public List<DeviceAccountDTO> listAccountDevices(String accountId) {
        return jdbc.query("SELECT * FROM device_account_rel WHERE account_id=? ORDER BY last_seen DESC LIMIT 200",
                REL, accountId);
    }

    public long relCount() {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM device_account_rel", Long.class);
        return v == null ? 0 : v;
    }

    // ---------------- IP 画像 ----------------

    private static final RowMapper<IpProfileDTO> IP = (rs, i) -> {
        IpProfileDTO d = new IpProfileDTO();
        d.setIp(rs.getString("ip"));
        d.setRegion(rs.getString("region"));
        d.setCountry(rs.getString("country"));
        d.setProxy(rs.getBoolean("is_proxy"));
        d.setIdc(rs.getBoolean("is_idc"));
        d.setAccountCount(rs.getInt("account_count"));
        d.setRejectCount(rs.getInt("reject_count"));
        d.setRiskScore(rs.getInt("risk_score"));
        d.setTags(rs.getString("tags"));
        d.setDetail(rs.getString("detail"));
        d.setSource(rs.getString("source"));
        d.setFirstSeen(rs.getLong("first_seen"));
        d.setLastSeen(rs.getLong("last_seen"));
        long ru = rs.getLong("retention_until");
        d.setRetentionUntil(rs.wasNull() ? null : ru);
        d.setExpired(rs.getBoolean("expired"));
        d.setRisky(d.getRiskScore() != null && d.getRiskScore() > 0);
        return d;
    };

    public IpProfileDTO getIp(String ip) {
        List<IpProfileDTO> list = jdbc.query("SELECT * FROM ip_profile WHERE ip=?", IP, ip);
        return list.isEmpty() ? null : list.get(0);
    }

    public void saveIp(IpProfileDTO d) {
        long now = System.currentTimeMillis();
        int updated = jdbc.update("UPDATE ip_profile SET region=?, country=?, is_proxy=?, is_idc=?,"
                        + " account_count=?, reject_count=?, risk_score=?, tags=?, detail=?, source=?,"
                        + " last_seen=?, retention_until=? WHERE ip=?",
                d.getRegion(), d.getCountry(), Boolean.TRUE.equals(d.getProxy()), Boolean.TRUE.equals(d.getIdc()),
                nz(d.getAccountCount()), nz(d.getRejectCount()), nz(d.getRiskScore()), d.getTags(), d.getDetail(),
                d.getSource(), now, d.getRetentionUntil(), d.getIp());
        if (updated > 0) {
            return;
        }
        try {
            jdbc.update("INSERT INTO ip_profile(ip,region,country,is_proxy,is_idc,account_count,reject_count,"
                            + "risk_score,tags,detail,source,first_seen,last_seen,retention_until,expired)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    d.getIp(), d.getRegion(), d.getCountry(), Boolean.TRUE.equals(d.getProxy()),
                    Boolean.TRUE.equals(d.getIdc()), nz(d.getAccountCount()), nz(d.getRejectCount()),
                    nz(d.getRiskScore()), d.getTags(), d.getDetail(), d.getSource(), now, now,
                    d.getRetentionUntil(), false);
        } catch (Exception e) {
            log.warn("IP 画像插入冲突（回退更新）ip={}: {}", d.getIp(), e.getMessage());
            jdbc.update("UPDATE ip_profile SET account_count=?, reject_count=?, risk_score=?, last_seen=?"
                    + " WHERE ip=?", nz(d.getAccountCount()), nz(d.getRejectCount()), nz(d.getRiskScore()), now,
                    d.getIp());
        }
    }

    public int countIpAccounts(String ip) {
        Integer v = jdbc.queryForObject("SELECT COUNT(DISTINCT payer_id) FROM behavior_sequence WHERE ip=?",
                Integer.class, ip);
        return v == null ? 0 : v;
    }

    public long ipCount() {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM ip_profile", Long.class);
        return v == null ? 0 : v;
    }

    // ---------------- 行为序列 ----------------

    private static final RowMapper<BehaviorEventDTO> BEHAVIOR = (rs, i) -> {
        BehaviorEventDTO d = new BehaviorEventDTO();
        d.setId(Long.parseLong(rs.getString("id")));
        d.setPayerId(rs.getString("payer_id"));
        d.setDeviceId(rs.getString("device_id"));
        d.setIp(rs.getString("ip"));
        d.setRegion(rs.getString("region"));
        d.setCountry(rs.getString("country"));
        d.setAction(rs.getString("action"));
        d.setAmount(rs.getLong("amount"));
        d.setTraceId(rs.getString("trace_id"));
        d.setCreatedAt(rs.getLong("created_at"));
        return d;
    };

    public void insertBehavior(BehaviorEventDTO d) {
        jdbc.update("INSERT INTO behavior_sequence(id,payer_id,device_id,ip,region,country,action,amount,"
                        + "trace_id,expired,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                String.valueOf(nextId("seq_behavior")), d.getPayerId(), d.getDeviceId(), d.getIp(), d.getRegion(),
                d.getCountry(), d.getAction(), d.getAmount(), d.getTraceId(), false,
                d.getCreatedAt() == null ? System.currentTimeMillis() : d.getCreatedAt());
    }

    public List<BehaviorEventDTO> listBehavior(String payerId, int limit) {
        if (payerId == null || payerId.isBlank()) {
            return jdbc.query("SELECT * FROM behavior_sequence ORDER BY created_at DESC, id DESC LIMIT " + limit,
                    BEHAVIOR);
        }
        return jdbc.query("SELECT * FROM behavior_sequence WHERE payer_id=?"
                + " ORDER BY created_at DESC, id DESC LIMIT " + limit, BEHAVIOR, payerId);
    }

    /** 窗口内行为样本（payerId 与 deviceKey 任一非空即按该维度过滤）。 */
    public List<BehaviorEventDTO> window(String payerId, String deviceKey, long since, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM behavior_sequence WHERE created_at>=?");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(since);
        if (payerId != null && !payerId.isBlank()) {
            sql.append(" AND payer_id=?");
            args.add(payerId);
        }
        if (deviceKey != null && !deviceKey.isBlank()) {
            sql.append(" AND device_id=?");
            args.add(deviceKey);
        }
        sql.append(" ORDER BY created_at ASC, id ASC LIMIT ").append(limit);
        return jdbc.query(sql.toString(), BEHAVIOR, args.toArray());
    }

    public long behaviorCount() {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM behavior_sequence", Long.class);
        return v == null ? 0 : v;
    }

    // ---------------- 留存期（匿名化，不删行） ----------------

    public int expireDevices(long before) {
        return jdbc.update("UPDATE device_fingerprint SET tags=NULL, detail=NULL, fingerprint_hash=NULL,"
                + " device_mask=NULL, expired=? WHERE retention_until<? AND expired=?", true, before, false);
    }

    public int expireIps(long before) {
        return jdbc.update("UPDATE ip_profile SET tags=NULL, detail=NULL, expired=?"
                + " WHERE retention_until<? AND expired=?", true, before, false);
    }

    public int expireBehavior(long before) {
        return jdbc.update("UPDATE behavior_sequence SET ip=NULL, device_id=NULL, trace_id=NULL, expired=?"
                + " WHERE created_at<? AND expired=?", true, before, false);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}