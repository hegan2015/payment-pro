package com.qoder.payment.risk.config;

/**
 * R-5 设备指纹/IP 画像 与 R-6 AML 的动态配置键与代码默认值（风控域自有键，不改 payment-common）。
 *
 * <p><b>默认值红线（继承 P1-B / §0.7 ride 兼容）</b>：
 * <ul>
 *   <li>内置保守规则口径（{@code payment.risk.single-limit=10000000} / {@code window-millis=10000}
 *       / {@code window-max=5} / {@code fee-bps=60}）<b>一个字节都不动</b>，本类不声明这些键；</li>
 *   <li>新画像因子默认<b>影子</b>：{@code risk.device.profile-score-enabled=false} /
 *       {@code risk.ip.profile-score-enabled=false} → DEVICE/IP 因子画像贡献恒 0 分；</li>
 *   <li>AML 默认<b>只报告不拦截</b>：{@code risk.aml.hard-block-enabled=false}，
 *       内置规则 {@code hardRule=false}；AML 评分贡献亦默认关闭（{@code risk.aml.score-enabled=false}）；</li>
 *   <li>ride/smoke 真实支付不带 deviceId/ip（payment 域调用点只填 4 字段）→ 画像采集直接跳过，
 *       零额外查询、零评分影响。</li>
 * </ul>
 *
 * <p>Apollo namespace 归属：全部属 {@code risk-rules}（与 P1-B 四键同 namespace，热更 + 5s 轮询）。
 */
public final class RiskPlusKeys {

    // ---------- R-5 设备指纹 ----------
    /** 画像采集总开关（关闭即完全不写画像表、不查画像）。 */
    public static final String DEVICE_ENABLED = "risk.device.enabled";
    public static final boolean DEFAULT_DEVICE_ENABLED = true;
    /** 画像接入既有 DEVICE 因子评分的开关，默认 false（新因子默认影子）。 */
    public static final String DEVICE_PROFILE_SCORE = "risk.device.profile-score-enabled";
    public static final boolean DEFAULT_DEVICE_PROFILE_SCORE = false;
    /** 同设备关联账户数阈值（≥ 即风险信号）。 */
    public static final String DEVICE_MULTI_ACCOUNT = "risk.device.multi-account-threshold";
    public static final int DEFAULT_DEVICE_MULTI_ACCOUNT = 5;
    /** 指纹属性漂移次数阈值（≥ 即 UNSTABLE）。 */
    public static final String DEVICE_DRIFT = "risk.device.drift-threshold";
    public static final int DEFAULT_DEVICE_DRIFT = 3;
    /** 设备标识脱敏存储（哈希键 + 掩码展示），默认开。 */
    public static final String DEVICE_MASK = "risk.device.mask-enabled";
    public static final boolean DEFAULT_DEVICE_MASK = true;
    /** 画像留存期（天），到期匿名化不删行。 */
    public static final String DEVICE_RETENTION_DAYS = "risk.device.retention-days";
    public static final int DEFAULT_DEVICE_RETENTION_DAYS = 90;

    // ---------- R-5 IP 画像 ----------
    public static final String IP_PROFILE_SCORE = "risk.ip.profile-score-enabled";
    public static final boolean DEFAULT_IP_PROFILE_SCORE = false;
    public static final String IP_MULTI_ACCOUNT = "risk.ip.multi-account-threshold";
    public static final int DEFAULT_IP_MULTI_ACCOUNT = 10;
    /** 历史拒绝次数阈值（本域 risk_decision_log 聚合）。 */
    public static final String IP_REJECT_COUNT = "risk.ip.history-reject-threshold";
    public static final int DEFAULT_IP_REJECT_COUNT = 3;
    public static final String IP_RETENTION_DAYS = "risk.ip.retention-days";
    public static final int DEFAULT_IP_RETENTION_DAYS = 90;
    /** 离线沙箱 CIDR 表覆盖（JSON 数组）；默认空=用内置沙箱表，绝不连外部情报 API。 */
    public static final String IP_SANDBOX_JSON = "risk.ip.sandbox.json";

    // ---------- R-5 行为序列 ----------
    public static final String BEHAVIOR_ENABLED = "risk.behavior.enabled";
    public static final boolean DEFAULT_BEHAVIOR_ENABLED = true;
    public static final String BEHAVIOR_WINDOW = "risk.behavior.window-millis";
    public static final long DEFAULT_BEHAVIOR_WINDOW = 60_000L;
    /** 窗口内笔数上限（≥ 即 burst 异常）。 */
    public static final String BEHAVIOR_MAX = "risk.behavior.max-count";
    public static final int DEFAULT_BEHAVIOR_MAX = 20;
    public static final String BEHAVIOR_RETENTION_DAYS = "risk.behavior.retention-days";
    public static final int DEFAULT_BEHAVIOR_RETENTION_DAYS = 30;

    // ---------- R-6 AML ----------
    public static final String AML_ENABLED = "risk.aml.enabled";
    public static final boolean DEFAULT_AML_ENABLED = true;
    /** 规则集 JSON（空=内置默认规则；非空则整体覆盖，解析失败保留 last-known-good）。 */
    public static final String AML_RULES_JSON = "risk.aml.rules.json";
    /** 单笔大额阈值（分，默认 5 万元）。 */
    public static final String AML_LARGE_AMOUNT = "risk.aml.large-amount";
    public static final long DEFAULT_AML_LARGE_AMOUNT = 5_000_000L;
    /** 日累计大额阈值（分）。 */
    public static final String AML_DAY_AMOUNT = "risk.aml.day-amount";
    public static final long DEFAULT_AML_DAY_AMOUNT = 5_000_000L;
    /** 拆分：日笔数下限。 */
    public static final String AML_SPLIT_COUNT = "risk.aml.split-count";
    public static final int DEFAULT_AML_SPLIT_COUNT = 10;
    /** 结构化：贴近阈值下方的笔数下限。 */
    public static final String AML_STRUCTURING_COUNT = "risk.aml.structuring-count";
    public static final int DEFAULT_AML_STRUCTURING_COUNT = 3;
    /** 结构化：贴近比例（百分数，amount ≥ threshold×ratio% 且 < threshold）。 */
    public static final String AML_STRUCTURING_RATIO = "risk.aml.structuring-ratio";
    public static final int DEFAULT_AML_STRUCTURING_RATIO = 90;
    /** 快进快出窗口（毫秒，默认 10 分钟）。 */
    public static final String AML_FAST_WINDOW = "risk.aml.fast-window-millis";
    public static final long DEFAULT_AML_FAST_WINDOW = 600_000L;
    /** 快进快出：出账/入账比例下限（百分数）。 */
    public static final String AML_FAST_RATIO = "risk.aml.fast-ratio";
    public static final int DEFAULT_AML_FAST_RATIO = 90;
    /** 异常时段（逗号分隔小时，Asia/Shanghai）。 */
    public static final String AML_NIGHT_HOURS = "risk.aml.night-hours";
    public static final String DEFAULT_AML_NIGHT_HOURS = "0,1,2,3,4,5";
    public static final String AML_NIGHT_AMOUNT = "risk.aml.night-amount";
    public static final long DEFAULT_AML_NIGHT_AMOUNT = 1_000_000L;
    public static final String AML_CROSS_BORDER_AMOUNT = "risk.aml.cross-border-amount";
    public static final long DEFAULT_AML_CROSS_BORDER_AMOUNT = 1_000_000L;
    /** 硬规则阻断总开关，默认 false（AML 上报不阻断正常交易）。 */
    public static final String AML_HARD_BLOCK = "risk.aml.hard-block-enabled";
    public static final boolean DEFAULT_AML_HARD_BLOCK = false;
    /** AML 命中接入既有评分（RULE 因子累加）的开关，默认 false。 */
    public static final String AML_SCORE_ENABLED = "risk.aml.score-enabled";
    public static final boolean DEFAULT_AML_SCORE_ENABLED = false;
    /** 名单筛查（制裁/PEP 沙箱名单）。 */
    public static final String AML_SCREENING = "risk.aml.screening-enabled";
    public static final boolean DEFAULT_AML_SCREENING = true;
    /** 达到人审档时经 DisputeFacade 建单（strategy=MANUAL 不动账）。 */
    public static final String AML_DISPUTE = "risk.aml.dispute-enabled";
    public static final boolean DEFAULT_AML_DISPUTE = true;
    /** STR 留存期（天，默认 5 年；只增不删）。 */
    public static final String AML_RETENTION_DAYS = "risk.aml.retention-days";
    public static final int DEFAULT_AML_RETENTION_DAYS = 1825;
    /** 自动跑批开关，默认 false（与 recon/settle/accounting 先例一致，绝不干扰既有断言）。 */
    public static final String AML_AUTO_SCAN = "risk.aml.auto-scan-enabled";
    public static final boolean DEFAULT_AML_AUTO_SCAN = false;
    public static final String AML_SCAN_INTERVAL = "risk.aml.scan-interval-ms";
    public static final long DEFAULT_AML_SCAN_INTERVAL = 60_000L;

    /** 画像/AML 命中判定满分（接入既有 DEVICE/IP 因子权重，不新增权重体系）。 */
    public static final int PROFILE_RISK_SCORE = 100;

    private RiskPlusKeys() {
    }
}