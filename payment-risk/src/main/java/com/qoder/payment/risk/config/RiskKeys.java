package com.qoder.payment.risk.config;

/**
 * R-1~R-4 动态配置键与代码默认值（风控域自有键；不改动 payment-common 的 ConfigKeys）。
 *
 * <p><b>默认值红线（§0.7）</b>：内置保守规则（单笔限额 / 滑动窗口限频）仍读
 * {@code payment.risk.single-limit=10000000} / {@code window-millis=10000} / {@code window-max=5}
 * （{@code ConfigKeys} 单一事实来源，口径逐字节不变）。本类新增键的默认值一律「宽松/空」：
 * <ul>
 *   <li>规则集 / 限额集 / 名单集默认 <b>空</b> → 新能力对正常流量零影响；</li>
 *   <li>全局影子模式默认 <b>true</b> → 即便误下发规则也只记录不拦截；</li>
 *   <li>评分因子阈值默认高 / 风险设备·IP·时段名单默认空 → 正常流量 score=0 → PASS。</li>
 * </ul>
 *
 * <p>Apollo namespace 归属：全部属 {@code risk-rules}（I-2 已声明该 namespace）。
 */
public final class RiskKeys {

    // ---------- 配置载荷（JSON，DynamicConfig 下发；DB 快照为版本化权威） ----------
    public static final String RULES_JSON = "risk.rules.json";
    public static final String QUOTAS_JSON = "risk.quota.rules.json";
    public static final String LISTS_JSON = "risk.list.entries.json";
    public static final String THRESHOLDS_JSON = "risk.score.thresholds.json";

    // ---------- R-1 引擎开关与护栏 ----------
    public static final String ENGINE_ENABLED = "risk.engine.enabled";
    public static final boolean DEFAULT_ENGINE_ENABLED = true;
    public static final String GLOBAL_SHADOW = "risk.rules.shadow";
    public static final boolean DEFAULT_GLOBAL_SHADOW = true;
    public static final String RULE_TIMEOUT_MS = "risk.rules.timeout-ms";
    public static final long DEFAULT_RULE_TIMEOUT_MS = 200L;
    public static final String RULE_HARD_TIMEOUT_MS = "risk.rules.hard-timeout-ms";
    public static final long DEFAULT_RULE_HARD_TIMEOUT_MS = 400L;

    // ---------- R-2 限额 ----------
    public static final String QUOTA_ENABLED = "risk.quota.enabled";
    public static final boolean DEFAULT_QUOTA_ENABLED = true;
    /**
     * 强制内存计数（默认 false）：docker 下用于验证「Redis 计数 ≡ local 内存计数」等价性，
     * 亦是 Redis 故障时的降级靶（{@code RedisQuotaCounter} 内部同款实现）。
     */
    public static final String QUOTA_FORCE_MEMORY = "risk.quota.force-memory";
    public static final boolean DEFAULT_QUOTA_FORCE_MEMORY = false;

    // ---------- R-3 名单 ----------
    public static final String LIST_ENABLED = "risk.list.enabled";
    public static final boolean DEFAULT_LIST_ENABLED = true;

    // ---------- R-4 评分因子 ----------
    public static final String SCORE_ENABLED = "risk.score.enabled";
    public static final boolean DEFAULT_SCORE_ENABLED = true;
    /** 频率因子：窗口内笔数 ≥ 该值才加分（默认 5，与限频上限同量级，正常流量不触发）。 */
    public static final String SCORE_FREQ_THRESHOLD = "risk.score.freq-threshold";
    public static final int DEFAULT_SCORE_FREQ_THRESHOLD = 5;
    public static final String SCORE_FREQ_WINDOW = "risk.score.freq-window-millis";
    public static final long DEFAULT_SCORE_FREQ_WINDOW = 60_000L;
    /** 金额偏离因子：本笔 > 历史均额 × 倍数 才加分。 */
    public static final String SCORE_AMOUNT_RATIO = "risk.score.amount-ratio";
    public static final int DEFAULT_SCORE_AMOUNT_RATIO = 5;
    /** 金额偏离因子：历史样本笔数下限（不足则不评分，避免冷启动误杀）。 */
    public static final String SCORE_AMOUNT_MIN_SAMPLE = "risk.score.amount-min-sample";
    public static final int DEFAULT_SCORE_AMOUNT_MIN_SAMPLE = 5;
    /**
     * 金额偏离因子的历史累计开关，默认 <b>false</b>：关闭时不写任何累计（正常流量零额外开销，
     * §0.7 ride 红线），偏离因子恒 0 分；开启后按「用户日累计金额/笔数」算均额。
     */
    public static final String SCORE_AMOUNT_HISTORY = "risk.score.amount-history";
    public static final boolean DEFAULT_SCORE_AMOUNT_HISTORY = false;
    /** 风险设备 / IP / 时段（逗号分隔；默认空=该因子恒 0 分）。 */
    public static final String SCORE_RISK_DEVICES = "risk.score.risk-devices";
    public static final String SCORE_RISK_IPS = "risk.score.risk-ips";
    public static final String SCORE_RISK_HOURS = "risk.score.risk-hours";
    /** 因子权重（JSON，可覆盖）。 */
    public static final String SCORE_WEIGHTS = "risk.score.weights.json";

    public static final int WEIGHT_FREQ = 25;
    public static final int WEIGHT_AMOUNT_DEV = 20;
    public static final int WEIGHT_LIST = 30;
    public static final int WEIGHT_DEVICE = 25;
    public static final int WEIGHT_IP = 20;
    public static final int WEIGHT_TIME = 10;

    // ---------- R-4 分级阈值默认值 ----------
    public static final int DEFAULT_CHALLENGE_SCORE = 40;
    public static final int DEFAULT_LIMIT_SCORE = 60;
    public static final int DEFAULT_REJECT_SCORE = 80;
    public static final int DEFAULT_REVIEW_SCORE = 95;
    /** LIMIT 档降额后的单笔上限（分，默认 1000 元）。 */
    public static final long DEFAULT_LIMIT_AMOUNT = 100_000L;

    /** 配置轮询周期（秒）：Apollo 推送即时，local 文件 30s reload + 本周期比对。 */
    public static final long POLL_SECONDS = 5;

    private RiskKeys() {
    }
}