package com.qoder.payment.accounting.daycut;

import com.qoder.payment.accounting.dataobject.AccountingDayDO;
import com.qoder.payment.accounting.dataobject.DaySnapshotDO;
import com.qoder.payment.accounting.mapper.AccountingDayMapper;
import com.qoder.payment.accounting.mapper.DaySnapshotMapper;
import com.qoder.payment.common.config.DynamicConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * AC-3 会计日历时钟：当前会计日解析 + 在途 TCC 受理日快照。
 *
 * <p><b>日切时点可配</b>（I-2 DynamicConfig，键 {@code accounting.daycut.at}，格式 HH:mm，默认 00:00）：
 * 默认与 settle/recon 的 Asia/Shanghai 自然日口径完全一致；改成例如 02:00 后，
 * 02:00 前的受理归属前一会计日。配置热更只影响<b>新受理</b>，在途单用受理时快照。
 *
 * <p>本组件<b>不依赖 AccountingService</b>（避免过账↔日切循环依赖），只读会计日历表。
 * 每次过账解析一次当前 OPEN 日（单行主键查询），日切后立即生效，无缓存陈旧风险。
 */
@Slf4j
@Component
@DependsOn("accountingSchemaUpgrade")
public class AccountingDayClock implements InitializingBean {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final long DAY_MS = 86_400_000L;
    public static final String KEY_CUT_AT = "accounting.daycut.at";
    public static final String DEFAULT_CUT_AT = "00:00";
    /** 自动日切开关：默认 false —— 不自动跑批干扰既有断言（由 XXL/控制台/ppjob 显式触发）。 */
    public static final String KEY_AUTO = "accounting.daycut.auto-enabled";

    private final AccountingDayMapper dayMapper;
    private final DaySnapshotMapper snapshotMapper;
    private final DynamicConfig dynamicConfig;

    public AccountingDayClock(AccountingDayMapper dayMapper, DaySnapshotMapper snapshotMapper,
                              DynamicConfig dynamicConfig) {
        this.dayMapper = dayMapper;
        this.snapshotMapper = snapshotMapper;
        this.dynamicConfig = dynamicConfig;
    }

    @Override
    public void afterPropertiesSet() {
        long day = currentDay();
        log.info("AC-3 会计日历就绪 currentDay={} cutAt={} provider={}", dayText(day), cutAt(), dynamicConfig.provider());
    }

    /** 日切时点（HH:mm），非法值回落默认 00:00（绝不因配置错误阻断过账）。 */
    public String cutAt() {
        String raw = dynamicConfig.get(KEY_CUT_AT, DEFAULT_CUT_AT);
        return parseCutAt(raw) == null ? DEFAULT_CUT_AT : raw.trim();
    }

    /** 是否允许本地 @Scheduled 自动日切（默认 false，共享栈上绝不自动跑批）。 */
    public boolean autoCutEnabled() {
        return dynamicConfig.getBool(KEY_AUTO, false);
    }

    private static LocalTime parseCutAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split(":");
        try {
            int hour = Integer.parseInt(parts[0].trim());
            int minute = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return null;
            }
            return LocalTime.of(hour, minute);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 时间戳 → 会计日（按配置的日切时点分桶，返回该会计日起点毫秒）。 */
    public long accountingDayOf(long epochMillis) {
        LocalTime cut = parseCutAt(cutAt());
        ZonedDateTime zdt = Instant.ofEpochMilli(epochMillis).atZone(ZONE);
        LocalDate date = zdt.toLocalDate();
        if (cut != null && zdt.toLocalTime().isBefore(cut)) {
            date = date.minusDays(1);
        }
        return date.atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    /** 当前会计日 = 最近一个 OPEN 日；无 OPEN 日则按当前时点建日（幂等）。 */
    public long currentDay() {
        AccountingDayDO open = dayMapper.selectOpen();
        if (open != null) {
            return open.getBizDate();
        }
        long day = accountingDayOf(System.currentTimeMillis());
        ensureDay(day);
        return day;
    }

    /** 幂等建日（只 INSERT 缺失行，绝不覆盖既有日行）。 */
    public AccountingDayDO ensureDay(long bizDate) {
        AccountingDayDO existing = dayMapper.select(bizDate);
        if (existing != null) {
            return existing;
        }
        AccountingDayDO fresh = new AccountingDayDO();
        fresh.setBizDate(bizDate);
        fresh.setStatus("OPEN");
        fresh.setOpenedAt(System.currentTimeMillis());
        try {
            dayMapper.insert(fresh);
        } catch (DuplicateKeyException ignored) {
            // 并发建日竞争：对手已插入
        }
        return dayMapper.select(bizDate);
    }

    /**
     * 在途 TCC 受理日快照（Try/freeze 时落库，只追加）。
     * 日切后 Confirm 仍按受理日归属凭证 —— 日切不破坏在途单。
     */
    public void snapshot(String bizNo) {
        if (bizNo == null || bizNo.isBlank()) {
            return;
        }
        try {
            if (snapshotMapper.select(bizNo) != null) {
                return;
            }
            DaySnapshotDO row = new DaySnapshotDO();
            row.setBizNo(bizNo);
            row.setBizDate(currentDay());
            row.setCreatedAt(System.currentTimeMillis());
            snapshotMapper.insert(row);
        } catch (DuplicateKeyException ignored) {
            // 并发 Try：对手已落快照
        } catch (Exception e) {
            // 快照失败绝不阻断冻结（资金可用性优先）：Confirm 回落当前会计日
            log.warn("AC-3 在途单会计日快照失败（Confirm 将回落当前日）bizNo={} err={}", bizNo, e.toString());
        }
    }

    /** 凭证归属会计日：优先受理时快照，无快照（升级前在途单/直接过账）用当前日。 */
    public long dayFor(String bizNo) {
        if (bizNo != null && !bizNo.isBlank()) {
            try {
                DaySnapshotDO row = snapshotMapper.select(bizNo);
                if (row != null && row.getBizDate() != null) {
                    return row.getBizDate();
                }
            } catch (Exception e) {
                log.warn("AC-3 会计日快照读取失败，回落当前日 bizNo={} err={}", bizNo, e.toString());
            }
        }
        return currentDay();
    }

    public static long dayStart(long epochMillis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE).atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    public static long today() {
        return dayStart(System.currentTimeMillis());
    }

    public static String dayText(long epochMillis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE).toString();
    }

    public static long nz(Long value) {
        return value == null ? 0L : value;
    }
}