package com.qoder.payment.channel.config;

/**
 * CH-3/CH-5 渠道域动态配置键（I-2 DynamicConfig 读取，local 文件 / docker Apollo namespace channel-route）。
 *
 * <p>说明：{@code payment-common} 的 {@code ConfigKeys} 为跨域基线键（冻结，本泳道不改），
 * 渠道域键按 §0.6 约定集中在此单一事实来源，全部带代码默认值，配置中心不可达时默认值兜底。
 *
 * <p><b>默认值红线</b>：默认路由必须选中 MOCK_BANK（同步模拟银行），保证
 * BALANCE/无渠道分支与 CARD 同步路径行为逐字节不变（§0.7）；渠道成本 feeBps 只参与路由打分，
 * <b>不参与支付手续费计算</b>（手续费仍由 payment.fee-bps 决定，口径不变）。
 */
public final class ChannelConfigKeys {

    /** 路由总开关（false=退回「默认渠道直连」，仍走 SPI，用于一键回滚）。 */
    public static final String ROUTE_ENABLED = "channel.route.enabled";
    public static final boolean DEFAULT_ROUTE_ENABLED = true;

    /** 默认/兜底渠道（历史行为等价键）。 */
    public static final String ROUTE_DEFAULT_CHANNEL = "channel.route.default-channel";
    public static final String DEFAULT_ROUTE_CHANNEL = "MOCK_BANK";

    /** 打分权重：成功率 / 成本 / 延迟 / 配置权重。 */
    public static final String ROUTE_WEIGHT_SUCCESS = "channel.route.weight.success";
    public static final String ROUTE_WEIGHT_COST = "channel.route.weight.cost";
    public static final String ROUTE_WEIGHT_LATENCY = "channel.route.weight.latency";
    public static final String ROUTE_WEIGHT_CONFIG = "channel.route.weight.config";
    public static final double DEFAULT_WEIGHT_SUCCESS = 1.0;
    public static final double DEFAULT_WEIGHT_COST = 1.0;
    public static final double DEFAULT_WEIGHT_LATENCY = 1.0;
    public static final double DEFAULT_WEIGHT_CONFIG = 1.0;

    /** 成功率滑动窗口（毫秒）与样本下限。 */
    public static final String ROUTE_WINDOW_MS = "channel.route.metrics-window-ms";
    public static final long DEFAULT_WINDOW_MS = 60_000L;
    public static final String ROUTE_BREAKER_MIN_SAMPLES = "channel.route.breaker.min-samples";
    public static final int DEFAULT_BREAKER_MIN_SAMPLES = 5;
    public static final String ROUTE_BREAKER_FAIL_RATE = "channel.route.breaker.fail-rate";
    public static final double DEFAULT_BREAKER_FAIL_RATE = 0.5;
    public static final String ROUTE_BREAKER_COOLDOWN_MS = "channel.route.breaker.cooldown-ms";
    public static final long DEFAULT_BREAKER_COOLDOWN_MS = 10_000L;

    /** 故障切换开关与最大候选尝试数（含主渠道）。 */
    public static final String ROUTE_FAILOVER_ENABLED = "channel.route.failover.enabled";
    public static final boolean DEFAULT_FAILOVER_ENABLED = true;
    public static final String ROUTE_MAX_ATTEMPTS = "channel.route.max-attempts";
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    /**
     * 主渠道偏好加成（分）：默认渠道（channel.route.default-channel=MOCK_BANK）在评分上额外加成，
     * 使默认态稳定落在既有同步模拟银行主渠道（§0.7 行为不变红线），不被偶发单笔拒绝的
     * 滑动窗口成功率抖动掀翻；成功率显著劣化（失败率 &gt; ~30%）或硬过滤（维护/限额/熔断）时
     * 自动让位备选渠道。设 0 即纯评分择优（无主渠道偏好）。
     */
    public static final String ROUTE_PRIMARY_BONUS = "channel.route.primary-bonus";
    public static final double DEFAULT_PRIMARY_BONUS = 30.0;

    /** 沙箱渠道总开关（false 时路由排除，local 默认零依赖不受影响）。 */
    public static final String SANDBOX_ENABLED = "channel.sandbox.enabled";
    public static final boolean DEFAULT_SANDBOX_ENABLED = true;
    /** 沙箱回执延迟（毫秒）与回执推送开关（故障注入的全局默认值）。 */
    public static final String SANDBOX_DELAY_MS = "channel.sandbox.delay-ms";
    public static final long DEFAULT_SANDBOX_DELAY_MS = 1500L;
    public static final String SANDBOX_RECEIPT_ENABLED = "channel.sandbox.receipt.enabled";
    public static final boolean DEFAULT_SANDBOX_RECEIPT_ENABLED = true;
    /** 沙箱 HTTP 端点（本进程内模拟银行侧，真实 HTTP 往返；无外部银行凭据）。 */
    public static final String SANDBOX_BASE_URL = "channel.sandbox.base-url";
    public static final String DEFAULT_SANDBOX_BASE_URL = "http://127.0.0.1:18087";
    public static final String SANDBOX_TIMEOUT_MS = "channel.sandbox.timeout-ms";
    public static final int DEFAULT_SANDBOX_TIMEOUT_MS = 2000;
    /** 沙箱渠道侧限额（分，0=不限）：与平台配置取更严者。 */
    public static final String SANDBOX_SINGLE_LIMIT = "channel.sandbox.single-limit";
    public static final String SANDBOX_DAILY_LIMIT = "channel.sandbox.daily-limit";

    /** 对账文件下载作业开关与落盘根目录（双 profile 同构）。 */
    public static final String BILL_ENABLED = "channel.bill.enabled";
    public static final boolean DEFAULT_BILL_ENABLED = true;
    public static final String BILL_DIR = "channel.bill.dir";
    public static final String DEFAULT_BILL_DIR = "./data/bills";
    /** 账单差异是否自动建差错单（source=PAYMENT，经 DisputeFacade，不改 recon 域）。 */
    public static final String BILL_DISPUTE_ENABLED = "channel.bill.dispute.enabled";
    public static final boolean DEFAULT_BILL_DISPUTE_ENABLED = true;

    private ChannelConfigKeys() {
    }

    // ---------- 渠道级键（动态下发优先于 channel_config） ----------

    public static String status(String code) {
        return "channel." + code + ".status";
    }

    public static String singleLimit(String code) {
        return "channel." + code + ".single-limit";
    }

    public static String dailyLimit(String code) {
        return "channel." + code + ".daily-limit";
    }

    public static String maintenanceStart(String code) {
        return "channel." + code + ".maintenance-start";
    }

    public static String maintenanceEnd(String code) {
        return "channel." + code + ".maintenance-end";
    }

    /** 每日循环维护窗口 "HH:mm-HH:mm"（Asia/Shanghai），与 epoch 区间并联判定。 */
    public static String maintenanceWindow(String code) {
        return "channel." + code + ".maintenance-window";
    }

    public static String weight(String code) {
        return "channel." + code + ".weight";
    }

    public static String feeBps(String code) {
        return "channel." + code + ".fee-bps";
    }
}