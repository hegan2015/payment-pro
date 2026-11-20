package com.qoder.payment.marketing.service;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.marketing.MarketingAuditDTO;
import com.qoder.payment.marketing.mapper.MarketingAuditMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * X-3 营销域公共支撑：取号 / 参数校验 / 审计留痕 / 动态配置读取。
 *
 * <p>取号走单库序列 {@code seq_marketing}（本域双 profile 均不分片，无跨片重号风险 §0.5 E-7）。
 * 配置一律经 {@link DynamicConfig}（I-2：local 文件 / docker Apollo），缺失回退代码默认值，
 * 配置中心不可达不阻断业务；<b>受理时快照落单据</b>（如积分兑换率落 points_log），热更不影响在途单。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketingSupport {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final long DAY_MS = 86_400_000L;

    /** 资金凭证前缀：营销域所有 accounting bizNo 均以此开头，fundCheck 据此精确归集（不受其它泳道影响）。 */
    public static final String FUND_PREFIX = "MKT:";
    public static final String FUND_REFUND_PREFIX = "MKTR:";
    /** 资金凭证归集前缀（同时覆盖核销 MKT: 与退回 MKTR:，fundCheck 据此精确统计 SYS_MARKETING 进出）。 */
    public static final String FUND_FILTER_PREFIX = "MKT";

    public static final String KEY_POINTS_RATE = "marketing.points.exchange-rate";
    public static final String KEY_POINTS_VALID_DAYS = "marketing.points.valid-days";
    public static final String KEY_VOUCHER_VALID_DAYS = "marketing.voucher.valid-days";
    public static final String KEY_ENABLED = "marketing.enabled";

    private final JdbcTemplate jdbc;
    private final MarketingAuditMapper auditMapper;
    private final ObjectProvider<DynamicConfig> dynamicConfig;

    @Value("${payment.marketing.points-exchange-rate:100}")
    private long defaultPointsRate;

    @Value("${payment.marketing.points-valid-days:365}")
    private int defaultPointsValidDays;

    @Value("${payment.marketing.voucher-valid-days:30}")
    private int defaultVoucherValidDays;

    public String newId(String prefix) {
        return prefix + nextSequence();
    }

    /**
     * 取号（双 profile 同构、跨实例不重号）：
     * <ul>
     *   <li>local H2：{@code SELECT next_value_for('seq_marketing')}（schema.sql 建的序列 + 别名）；</li>
     *   <li>docker MySQL：{@code UPDATE sequences SET value=LAST_INSERT_ID(value+1)} + {@code SELECT LAST_INSERT_ID()}
     *       <b>同一 Connection</b> 内执行（ConnectionCallback 保证），行锁原子 +1，与存储函数口径等价，
     *       且不需要 SUPER / log_bin_trust_function_creators 权限。</li>
     * </ul>
     * 本域双 profile 均不分片，单库单序列即全局唯一（无 §0.5 E-7 跨片重号风险）。
     */
    private long nextSequence() {
        if (isH2()) {
            Long value = jdbc.queryForObject("SELECT next_value_for('seq_marketing')", Long.class);
            return value == null ? 0L : value;
        }
        Long value = jdbc.execute((ConnectionCallback<Long>) con -> {
            try (java.sql.Statement st = con.createStatement()) {
                int rows = st.executeUpdate(
                        "UPDATE sequences SET value = LAST_INSERT_ID(value + 1) WHERE name = 'seq_marketing'");
                if (rows != 1) {
                    throw new IllegalStateException("营销域取号失败：sequences 缺 seq_marketing 种子行");
                }
                try (java.sql.ResultSet rs = st.executeQuery("SELECT LAST_INSERT_ID()")) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
        return value == null ? 0L : value;
    }

    private volatile Boolean h2;

    private boolean isH2() {
        Boolean cached = h2;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (h2 == null) {
                Boolean detected = jdbc.execute((ConnectionCallback<Boolean>) con -> {
                    String product = con.getMetaData().getDatabaseProductName();
                    return product != null && product.toUpperCase().contains("H2");
                });
                h2 = Boolean.TRUE.equals(detected);
            }
            return h2;
        }
    }

    public static long nz(Long value) {
        return value == null ? 0L : value;
    }

    public static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    public static void require(boolean condition, String message) {
        if (!condition) {
            throw new BizException(ResultCode.PARAM_ERROR, message);
        }
    }

    public static void requireBiz(boolean condition, String message) {
        if (!condition) {
            throw new BizException(ResultCode.BIZ_ERROR, message);
        }
    }

    public static void requireFound(Object value, String message) {
        if (value == null) {
            throw new BizException(ResultCode.NOT_FOUND, message);
        }
    }

    public static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 幂等键长度护栏：idempotent_record.biz_no / accounting transfer_record.biz_no 均为 VARCHAR(64)。 */
    public static void requireKey(String key) {
        require(notBlank(key), "幂等键必填");
        require(key.length() <= 64, "幂等键过长（≤64 字符）: " + key.length());
    }

    public long pointsRate() {
        DynamicConfig config = dynamicConfig.getIfAvailable();
        long rate = config == null ? defaultPointsRate : config.getLong(KEY_POINTS_RATE, defaultPointsRate);
        return rate <= 0 ? defaultPointsRate : rate;
    }

    public int pointsValidDays() {
        DynamicConfig config = dynamicConfig.getIfAvailable();
        int days = config == null ? defaultPointsValidDays : config.getInt(KEY_POINTS_VALID_DAYS, defaultPointsValidDays);
        return days <= 0 ? defaultPointsValidDays : days;
    }

    public int voucherValidDays() {
        DynamicConfig config = dynamicConfig.getIfAvailable();
        int days = config == null ? defaultVoucherValidDays
                : config.getInt(KEY_VOUCHER_VALID_DAYS, defaultVoucherValidDays);
        return days <= 0 ? defaultVoucherValidDays : days;
    }

    /** 营销域总开关（§5.5 回滚手段：禁用后组合支付退化为纯资金腿，由 P-7 编排侧读取）。 */
    public boolean enabled() {
        DynamicConfig config = dynamicConfig.getIfAvailable();
        return config == null || config.getBool(KEY_ENABLED, true);
    }

    public static long dayStart(long epochMillis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE).atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    public static long plusDays(long epochMillis, int days) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE).plusDays(days)
                .atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    /** 审计留痕（只追加；留痕失败不吞业务异常，由调用方事务一并回滚）。 */
    public void audit(String entityType, String entityId, String action, String fromStatus, String toStatus,
                      Long amount, String bizNo, String operator, String detail) {
        MarketingAuditDTO row = new MarketingAuditDTO();
        row.setId(newId("MA"));
        row.setEntityType(entityType);
        row.setEntityId(entityId);
        row.setAction(action);
        row.setFromStatus(fromStatus);
        row.setToStatus(toStatus);
        row.setAmount(amount);
        row.setBizNo(bizNo);
        row.setOperator(notBlank(operator) ? operator : "SYSTEM");
        row.setDetail(detail);
        row.setCreatedAt(System.currentTimeMillis());
        auditMapper.insert(row);
    }
}