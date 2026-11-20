package com.qoder.payment.common.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

/**
 * I-3 孤儿锁回收（启动时一次，只动 shedlock 协调表，绝不触碰业务数据）。
 *
 * <p>场景：进程被 kill -9 / 猝死时，ShedLock 的 {@code lock_until} 会保留到 lockAtMostFor 到期，
 * 期间其他实例只能跳过 —— 混沌验证（停 payment 再拉起）下会让补偿/投递停摆数分钟。
 *
 * <p>回收判据严格且保守：锁仍有效（lock_until &gt; now）+ {@code locked_by} 的主机名等于本机
 * + 该 pid 已不存在（{@link ProcessHandle#of(long)} 为空）→ 判定为孤儿锁，置 lock_until=0 释放。
 * 主机名不同（跨机部署）或 pid 仍存活（另一实例正常持锁）一律不动；pid 复用只会导致「不回收」（保守安全）。
 * 无法解析的 locked_by（如运维/脚本手工持锁）一律不动。
 */
public class ShedLockOrphanRecovery implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ShedLockOrphanRecovery.class);

    private final JdbcTemplate jdbc;
    private final String table;

    public ShedLockOrphanRecovery(JdbcTemplate jdbc, String table) {
        this.jdbc = jdbc;
        this.table = table;
    }

    @Override
    public void afterPropertiesSet() {
        String localHost;
        try {
            localHost = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            log.warn("I-3 取本机主机名失败，跳过孤儿锁回收: {}", e.toString());
            return;
        }
        long now = System.currentTimeMillis();
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("SELECT name, lock_until, locked_by FROM " + table);
        } catch (Exception e) {
            log.warn("I-3 读取锁表失败，跳过孤儿锁回收: {}", e.toString());
            return;
        }
        int released = 0;
        for (Map<String, Object> row : rows) {
            Object until = row.get("lock_until");
            Object by = row.get("locked_by");
            String name = String.valueOf(row.get("name"));
            if (!(until instanceof Number n) || n.longValue() <= now || by == null) {
                continue;
            }
            String lockedBy = by.toString();
            int slash = lockedBy.lastIndexOf('/');
            if (slash <= 0 || slash == lockedBy.length() - 1) {
                continue;
            }
            if (!lockedBy.substring(0, slash).equals(localHost)) {
                continue;
            }
            long pid;
            try {
                pid = Long.parseLong(lockedBy.substring(slash + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (ProcessHandle.of(pid).isPresent()) {
                continue;
            }
            int updated = jdbc.update("UPDATE " + table + " SET lock_until = 0 WHERE name = ? AND locked_by = ?",
                    name, lockedBy);
            if (updated > 0) {
                released++;
                log.warn("[告警] I-3 回收孤儿锁（持锁进程已消失）name={} lockedBy={} 原到期={}",
                        name, lockedBy, n.longValue());
            }
        }
        log.info("I-3 孤儿锁回收完成 host={} 扫描={} 释放={}", localHost, rows.size(), released);
    }
}