package com.qoder.payment.channel.sign;

/**
 * CH-6/X-4 签约代扣动态配置键（I-2 DynamicConfig：local 文件 / docker Apollo，全部带代码默认值）。
 *
 * <p>不改 {@code payment-common} 的 {@code ConfigKeys}（跨域基线冻结），本域键集中在此单一事实来源。
 * <p><b>默认值红线</b>：签约能力默认开启但<b>只作用于新增 /api/channel/sign* 端点</b>；
 * ride 无签约 ⇒ 不读这些键、不建任何行，既有支付/BALANCE 路径零影响（§0.7）。
 */
public final class SignConfigKeys {

    /** 签约代扣总开关（false=受理前拒绝签约与代扣，一键回滚；既有渠道支付路径不受影响）。 */
    public static final String ENABLED = "channel.sign.enabled";
    public static final boolean DEFAULT_ENABLED = true;

    /** 默认单笔授权限额（分，5 万元）。 */
    public static final String DEFAULT_SINGLE_LIMIT = "channel.sign.default.single-limit";
    public static final long DEFAULT_SINGLE_LIMIT_VALUE = 5_000_000L;

    /** 默认周期累计限额（分，20 万元）。 */
    public static final String DEFAULT_PERIOD_LIMIT = "channel.sign.default.period-limit";
    public static final long DEFAULT_PERIOD_LIMIT_VALUE = 20_000_000L;

    /** 默认周期口径 DAY/MONTH/NONE。 */
    public static final String DEFAULT_PERIOD_TYPE = "channel.sign.default.period-type";
    public static final String DEFAULT_PERIOD_TYPE_VALUE = "DAY";

    /** 默认协议有效期（天）。 */
    public static final String DEFAULT_VALID_DAYS = "channel.sign.default.valid-days";
    public static final int DEFAULT_VALID_DAYS_VALUE = 365;

    /** 默认扣款失败最大重试次数。 */
    public static final String DEFAULT_MAX_RETRY = "channel.sign.default.max-retry";
    public static final int DEFAULT_MAX_RETRY_VALUE = 3;

    /** 默认重试基础间隔（毫秒，按尝试次数线性退避）。 */
    public static final String DEFAULT_RETRY_INTERVAL_MS = "channel.sign.default.retry-interval-ms";
    public static final long DEFAULT_RETRY_INTERVAL_MS_VALUE = 60_000L;

    /** 重试/收敛作业单批上限。 */
    public static final String RETRY_BATCH_SIZE = "channel.sign.retry.batch-size";
    public static final int DEFAULT_RETRY_BATCH_SIZE = 50;

    /** 事件投递失败最大重试次数（超过留 FAILED，人工/作业可再驱动，绝不丢事件）。 */
    public static final String EVENT_MAX_RETRY = "channel.sign.event.max-retry";
    public static final int DEFAULT_EVENT_MAX_RETRY = 5;

    /** 事件投递重试基础间隔（毫秒）。 */
    public static final String EVENT_RETRY_INTERVAL_MS = "channel.sign.event.retry-interval-ms";
    public static final long DEFAULT_EVENT_RETRY_INTERVAL_MS = 30_000L;

    /** 列表查询默认上限（防止全表扫描）。 */
    public static final int LIST_LIMIT = 200;

    private SignConfigKeys() {
    }
}