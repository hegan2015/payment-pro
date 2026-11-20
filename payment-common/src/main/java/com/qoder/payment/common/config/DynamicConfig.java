package com.qoder.payment.common.config;

import java.util.Map;

/**
 * 动态配置 SPI（I-2 地基，跨域基础设施 §0.6）：费率/限额/规则/开关一律经本接口读取，
 * 替代散落的 {@code @Value} 硬编码，改配置不重启生效。
 *
 * <p>双 profile（{@code payment.config.apollo.enabled} 开关，AutoConfiguration 装配）：
 * <ul>
 *   <li>local 默认 {@link LocalFileDynamicConfig}：{@code ~/.config/payment-pro/config.properties}
 *       + 30s 定时 reload，零依赖、文件缺失用默认值不报错；</li>
 *   <li>docker {@link ApolloDynamicConfig}：apollo-client，namespace
 *       application / risk-rules / channel-route / settle-fee。</li>
 * </ul>
 *
 * <p><b>降级红线</b>：配置中心不可达 → last-known-good + 代码默认值，
 * 绝不因配置中心宕机阻断支付（与 MerchantCache 降级策略一致，不 fail-fast）。
 * <p><b>密钥红线</b>：密钥类配置不进配置中心明文（走 {@code SecretProvider}，S-1）。
 * <p><b>快照语义</b>：业务受理时读取的值应随单据落库（如 payment.fee），
 * 后续二阶段/结算/退款读单据快照，配置热更不得影响在途单。
 */
public interface DynamicConfig {

    /** 取字符串；缺失/不可达返回 {@code def}。 */
    String get(String key, String def);

    /** 取 long；缺失/解析失败/不可达返回 {@code def}。 */
    long getLong(String key, long def);

    /** 取 int；缺失/解析失败/不可达返回 {@code def}。 */
    int getInt(String key, int def);

    /** 取 boolean（true/false，忽略大小写）；缺失/非法值/不可达返回 {@code def}。 */
    boolean getBool(String key, boolean def);

    /** 注册 key 级变更监听（值变化回调；{@code newValue=null} 表示删除）。监听异常不影响其它监听与主流程。 */
    void addListener(String key, Listener listener);

    /** 当前原始配置快照（配置源里实际存在的键值；控制台只读展示用，脱敏由调用方负责）。 */
    Map<String, String> snapshot();

    /** 手动 reload：local 重读文件返回 true；Apollo 长轮询自动推送，返回 false。 */
    boolean refresh();

    /** 实现标识：{@code local-file} / {@code apollo}（控制台观测用）。 */
    String provider();

    /** 配置变更监听器。 */
    @FunctionalInterface
    interface Listener {
        void onChange(String key, String oldValue, String newValue);
    }
}