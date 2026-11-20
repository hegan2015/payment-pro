package com.qoder.payment.risk.store;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * R-5/R-6 增量建表（幂等、只增不改，双 profile 同构）。
 *
 * <p>local H2：执行 {@code schema-plus.sql}；docker MySQL（{@code sql.init.mode=never}）：
 * 执行 {@code schema-plus-mysql.sql}，在 Bean 阶段自动补建，<b>无需手工 ALTER、不改 ops-pro</b>。
 * <p>{@link com.qoder.payment.risk.device.DeviceStore} / {@code AmlStore} 构造注入本 Bean，
 * 保证建表早于任何读写。
 * <p><b>数据保护</b>：只 CREATE IF NOT EXISTS / INSERT IGNORE；绝不 DROP/TRUNCATE/DELETE。
 * 建表失败只 warn（画像/AML 属增值能力，绝不因自身 DDL 异常阻断支付主链路）。
 */
@Slf4j
@Component
public class RiskPlusSchemaInitializer {

    private final boolean mysql;
    private final boolean applied;

    public RiskPlusSchemaInitializer(JdbcTemplate jdbc) {
        boolean isMysql = false;
        try {
            String product = jdbc.execute((ConnectionCallback<String>)
                    c -> c.getMetaData().getDatabaseProductName());
            isMysql = product != null && product.toLowerCase().contains("mysql");
        } catch (Exception e) {
            log.warn("R-5/R-6 方言探测失败，按 H2 处理: {}", e.getMessage());
        }
        this.mysql = isMysql;
        this.applied = apply(jdbc, isMysql ? "schema-plus-mysql.sql" : "schema-plus.sql");
    }

    private static boolean apply(JdbcTemplate jdbc, String resource) {
        int ok = 0;
        int fail = 0;
        try {
            String text = new String(new ClassPathResource(resource).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            for (String raw : text.split(";\\s*\\n")) {
                String sql = stripComments(raw).trim();
                if (sql.isEmpty()) {
                    continue;
                }
                try {
                    jdbc.execute(sql);
                    ok++;
                } catch (Exception e) {
                    fail++;
                    log.warn("R-5/R-6 增量 DDL 单条失败（继续，不阻断）: {} | {}",
                            sql.substring(0, Math.min(80, sql.length())), e.getMessage());
                }
            }
            log.info("R-5/R-6 增量建表完成 resource={} ok={} fail={}", resource, ok, fail);
            return fail == 0;
        } catch (Exception e) {
            log.warn("R-5/R-6 增量建表脚本读取失败（画像/AML 降级为不可用，支付不受影响）: {}", e.getMessage());
            return false;
        }
    }

    private static String stripComments(String raw) {
        List<String> lines = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("--")) {
                continue;
            }
            lines.add(line);
        }
        return String.join("\n", lines);
    }

    public boolean mysql() {
        return mysql;
    }

    public boolean applied() {
        return applied;
    }
}