package com.qoder.payment.common.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * I-5b 分片路由状态持久化（<b>单库不分片</b>，回滚跨重启不丢）。
 *
 * <p>为什么需要：I-5a 的 {@link ShardRuntime#setActive(boolean)} 只在内存，进程重启即回默认值。
 * 对非资金域（append-only + 只 INSERT 复制）无害；对资金域<b>有害</b>——
 * 若切流后重启回到默认态，启动同步会把「较旧的影子侧」当成权威侧覆盖较新的业务行。
 * 本表把权威侧钉死：重启后按持久化状态恢复，启动同步方向永远正确。
 *
 * <p>表结构（只 CREATE IF NOT EXISTS，绝不 DROP/DELETE）：
 * <pre>shard_route_state(domain VARCHAR(64) PK, active BOOLEAN, updated_at BIGINT)</pre>
 *
 * <p>本类只用原单库 DataSource，不触碰 ShardingSphere；表须列入 {@code payment.sharding.single-tables}。
 */
public class ShardRouteState {

    private static final Logger log = LoggerFactory.getLogger(ShardRouteState.class);

    public static final String TABLE = "shard_route_state";

    private final DataSource raw;
    private final String domain;

    public ShardRouteState(DataSource raw, String domain) {
        this.raw = raw;
        this.domain = domain;
    }

    public void ensureTable() {
        try (Connection con = raw.getConnection(); Statement st = con.createStatement()) {
            con.setAutoCommit(true);
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "domain VARCHAR(64) NOT NULL PRIMARY KEY,"
                    + "active BOOLEAN NOT NULL,"
                    + "updated_at BIGINT NOT NULL)");
        } catch (SQLException e) {
            throw new IllegalStateException("I-5b 路由状态表建表失败: " + e.getMessage(), e);
        }
    }

    /** 读持久化状态；无行返回 null（首次启动，沿用 initial-active）。 */
    public Boolean read() {
        try (Connection con = raw.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT active FROM " + TABLE + " WHERE domain=?")) {
            ps.setString(1, domain);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBoolean(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("I-5b 路由状态读取失败（fail-fast，不臆断权威侧）: " + e.getMessage(), e);
        }
    }

    /** 写持久化状态（upsert，只影响本域一行元数据，绝不触碰业务行）。 */
    public void write(boolean active) {
        ensureTable();
        try (Connection con = raw.getConnection()) {
            con.setAutoCommit(true);
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE " + TABLE + " SET active=?, updated_at=? WHERE domain=?")) {
                ps.setBoolean(1, active);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, domain);
                if (ps.executeUpdate() > 0) {
                    log.warn("I-5b 分片路由状态已持久化 domain={} active={}（重启后按此恢复权威侧）", domain, active);
                    return;
                }
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO " + TABLE + "(domain,active,updated_at) VALUES(?,?,?)")) {
                ps.setString(1, domain);
                ps.setBoolean(2, active);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            log.warn("I-5b 分片路由状态已持久化 domain={} active={}（首次落库）", domain, active);
        } catch (SQLException e) {
            throw new IllegalStateException("I-5b 路由状态写入失败: " + e.getMessage(), e);
        }
    }

    public String domain() {
        return domain;
    }
}