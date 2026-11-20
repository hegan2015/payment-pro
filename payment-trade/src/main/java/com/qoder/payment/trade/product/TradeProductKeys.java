package com.qoder.payment.trade.product;

/**
 * T-3 产品参数键与代码默认值（I-2 DynamicConfig 单一事实来源）。
 *
 * <p><b>红线</b>：默认值只影响新增产品与新增 deadline 列，
 * 不触碰 {@code payment.fee-bps}（手续费口径）与 INSTANT 状态机；
 * 两个自动跑批开关默认 <b>false</b> —— 超时关闭/担保自动确认只由 XXL-Job、控制台或
 * {@code /actuator/ppjob} 显式触发，绝不自动跑批干扰既有断言。
 */
public final class TradeProductKeys {

    /** 缺省产品（未显式指定 productType 时路由到它）；默认 INSTANT。 */
    public static final String DEFAULT_PRODUCT = "trade.product.default";
    /** 产品启用开关前缀：trade.product.escrow.enabled / trade.product.preauth.enabled。 */
    public static final String PRODUCT_ENABLED_PREFIX = "trade.product.";
    public static final String PRODUCT_ENABLED_SUFFIX = ".enabled";
    /** 商户级产品路由覆盖：trade.product.merchant.<merchantId>=ESCROW（可选，按订单属性路由）。 */
    public static final String MERCHANT_ROUTE_PREFIX = "trade.product.merchant.";

    /** T-4 未支付订单超时关闭分钟数（默认 30，与计划 §3.C T-4 一致）。 */
    public static final String ORDER_EXPIRE_MINUTES = "trade.order.expire-minutes";
    public static final int DEFAULT_ORDER_EXPIRE_MINUTES = 30;
    /** T-4 单次扫描批量上限（默认 200）。 */
    public static final String EXPIRE_BATCH_SIZE = "trade.expire.batch-size";
    public static final int DEFAULT_EXPIRE_BATCH_SIZE = 200;
    /** T-4 本地 @Scheduled 自动扫描开关（默认 false：只由 XXL/控制台/ppjob 触发）。 */
    public static final String EXPIRE_AUTO_ENABLED = "trade.expire.auto.enabled";
    /** T-4 是否在支付受理（TCC Try）时校验未过期（默认 false ⇒ 默认产品受理路径逐字节不变）。 */
    public static final String EXPIRE_REJECT_PAY = "trade.expire.reject-pay";

    /** T-1 担保超时自动确认天数（默认 7，与计划 §3.C T-1 一致）。 */
    public static final String ESCROW_AUTO_CONFIRM_DAYS = "trade.escrow.auto-confirm-days";
    public static final int DEFAULT_ESCROW_AUTO_CONFIRM_DAYS = 7;
    /** T-1 本地 @Scheduled 自动确认开关（默认 false）。 */
    public static final String ESCROW_AUTO_ENABLED = "trade.escrow.auto.enabled";
    /** T-1 单次扫描批量上限（默认 200）。 */
    public static final String ESCROW_BATCH_SIZE = "trade.escrow.batch-size";
    public static final int DEFAULT_ESCROW_BATCH_SIZE = 200;
    /** T-1 平台担保系统商户名（沿用 SYS_FUND 先例的系统商户 + 余额户）。 */
    public static final String ESCROW_HOLD_MERCHANT = "trade.escrow.hold-merchant";
    public static final String DEFAULT_ESCROW_HOLD_MERCHANT = "SYS_ESCROW";

    /** T-2 预授权最长有效天数（默认 30；到期由撤销/完成收敛，本卡不自动撤销）。 */
    public static final String PREAUTH_MAX_DAYS = "trade.preauth.max-days";
    public static final int DEFAULT_PREAUTH_MAX_DAYS = 30;

    private TradeProductKeys() {
    }

    public static String enabledKey(TradeProduct product) {
        return PRODUCT_ENABLED_PREFIX + product.code().toLowerCase() + PRODUCT_ENABLED_SUFFIX;
    }
}