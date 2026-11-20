package com.qoder.payment.settle.service;

import com.qoder.payment.common.db.IdGenerator;
import com.qoder.payment.common.db.ShardRuntime;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Supplier;

/**
 * ST-x 公共支撑：取号 / 业务日分桶 / 读己之写。
 *
 * <p>取号：装配了全局发号（docker 分片态）即走雪花，避免各片 sequences 重号（E-7）；
 * 否则走单库序列 {@code seq_settle_st}（local H2 / docker 未分片）。新表 ID 前缀与 P0 结算单 S 区分。
 *
 * <p>业务日一律按 {@code Asia/Shanghai} 自然日切（与 AC-3 日切口径一致），bizDate = 当日 00:00 毫秒。
 */
@Component
@RequiredArgsConstructor
public class SettleSupport {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final long DAY_MS = 86_400_000L;

    private final JdbcTemplate jdbc;
    private final ObjectProvider<IdGenerator> idGenerator;
    private final ObjectProvider<ShardRuntime> shardRuntime;

    public String newId(String prefix) {
        IdGenerator generator = idGenerator.getIfAvailable();
        if (generator != null) {
            return generator.next(prefix);
        }
        Long value = jdbc.queryForObject("SELECT next_value_for('seq_settle_st')", Long.class);
        return prefix + (value == null ? 0L : value);
    }

    public <T> T readYourWrite(Supplier<T> action) {
        ShardRuntime runtime = shardRuntime.getIfAvailable();
        return runtime == null ? action.get() : runtime.readYourWrite(action);
    }

    public static long dayStart(long epochMillis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE).atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    public static long today() {
        return dayStart(System.currentTimeMillis());
    }

    public static long monthStart(long epochMillis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE).withDayOfMonth(1)
                .atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    public static long nextMonthStart(long monthStartMillis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(monthStartMillis), ZONE).plusMonths(1)
                .atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    public static String dayText(long epochMillis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE).toString();
    }

    public static String monthText(long epochMillis) {
        String day = dayText(epochMillis);
        return day.substring(0, 7);
    }

    public static long nz(Long value) {
        return value == null ? 0L : value;
    }
}