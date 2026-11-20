package com.qoder.payment.channel.spi;

import com.qoder.payment.facade.channel.ChannelLimitDTO;
import com.qoder.payment.facade.channel.ChannelPayRequest;
import com.qoder.payment.facade.channel.ChannelRefundRequest;
import com.qoder.payment.facade.channel.ChannelResult;

/**
 * CH-2a 渠道适配 SPI（channel 域内接口，非 Dubbo）：一个渠道 = 一个实现 + 一条 channel_config。
 *
 * <p>统一同步/异步语义：{@link ChannelResult#isProcessing()} → channel_trade SUBMITTED，
 * 走 CH-1 WAITING/回执/查询补单路径；SUCCESS/FAILED 同步收敛（与重构前模拟银行行为逐字一致）。
 *
 * <p><b>实现约束（资金护航）</b>：
 * <ul>
 *   <li>适配器只负责「渠道侧」受理/查询/退款/账单，<b>不得写 channel_trade</b>
 *       （本地流水由 ChannelService 单点落库，保证一个 bizNo 只有一行、一个渠道）；</li>
 *   <li>业务拒绝（风控/卡号规则）必须 {@code failoverAllowed=false}，基础设施失败且确未受理才可为 true；</li>
 *   <li>受理后状态未知（超时/网络中断）一律返回 PROCESSING，绝不臆断失败；</li>
 *   <li>密钥经 {@code SecretProvider}（S-1）取用，明文不落日志/DB。</li>
 * </ul>
 *
 * <p>新增渠道只需实现本接口并注册为 Spring Bean（{@link ChannelAdapterRegistry} 自动按
 * {@link #channelCode()} 收编），无需改动 ChannelService / TCC 参与者（开闭原则）。
 */
public interface ChannelAdapter {

    /** 渠道编码（路由 key，与 channel_config.channel_code 对应）。 */
    String channelCode();

    /** 适配器类型标识（MOCK/SANDBOX/ECHO/...）。 */
    String adapterType();

    /** 环境标识：SANDBOX / PROD（沙箱与生产隔离，CH-2b）。 */
    default String env() {
        return "PROD";
    }

    /** 是否异步受理适配器（true 时仅在 payment.channel.async=true 才可被路由选中）。 */
    default boolean asyncCapable() {
        return false;
    }

    /** 受理支付（TCC Try 的渠道侧动作）。 */
    ChannelResult pay(ChannelPayRequest request);

    /** 受理退款（原扣款必须已终态成功）。 */
    ChannelResult refund(ChannelRefundRequest request);

    /** 渠道侧查询（只读，绝不改写本地状态）；无此单返回 status=FAILED + reason=NOT_FOUND。 */
    ChannelResult query(String bizNo);

    /** 渠道侧关单/撤销（未受理单关闭；已终态单幂等 noop）。 */
    ChannelResult close(String bizNo);

    /**
     * 回执报文解析 + 验签（渠道 → 平台）：把渠道原始报文归一为 {@link ChannelResult}。
     * 默认实现按平台统一回执契约处理（无渠道私有报文/签名）。
     */
    default ChannelResult parseReceipt(String rawPayload) {
        throw new UnsupportedOperationException(channelCode() + " 未实现私有回执报文解析");
    }

    /** 是否支持对账文件下载（CH-4）。 */
    default boolean billSupported() {
        return false;
    }

    /** 下载/生成对账文件（CH-4）：bizDate=yyyyMMdd。 */
    default ChannelBillFile downloadBill(String bizDate) {
        throw new UnsupportedOperationException(channelCode() + " 不支持对账文件下载");
    }

    /**
     * 渠道侧限额查询（CH-5）：返回渠道方约束（null=无渠道侧约束，由平台 channel_config 决定）。
     * 路由取「平台配置与渠道侧约束的更严者」做硬过滤。
     */
    default ChannelLimitDTO queryLimit() {
        return null;
    }

    // ---------- CH-6 签约代扣 SPI（additive 默认方法：既有 4 个适配器与同步路径行为一字未改） ----------

    /**
     * 是否支持协议签约/解约（CH-6）。false ⇒ 签约受理前明确拒绝（不静默降级、不落协议行）。
     * 默认 false：未实现签约能力的渠道（如 ECHO 参考实现）不参与签约。
     */
    default boolean signCapable() {
        return false;
    }

    /**
     * 渠道侧签约（用户授权商户按期扣款）：返回 {@code channelOrderNo} 承载<b>渠道协议号</b>。
     * 沙箱/模拟银行实现，<b>不接真实银行/微信/支付宝委托扣款</b>；业务拒绝一律
     * {@link ChannelResult#declined(String)}（failoverAllowed=false，不切换掩盖拒绝原因）。
     *
     * @param signNo      平台协议号（渠道侧幂等键）
     * @param cardNo      卡号（明文只在调用链内，落库由平台侧 hash+mask+密文）
     * @param singleLimit 授权单笔限额（分，可空）
     */
    default ChannelResult sign(String signNo, String cardNo, Long singleLimit) {
        throw new UnsupportedOperationException(channelCode() + " 未实现签约 SPI（CH-6）");
    }

    /** 渠道侧解约（幂等：已解约重复调用返回 SUCCESS）。 */
    default ChannelResult unsign(String channelSignNo) {
        throw new UnsupportedOperationException(channelCode() + " 未实现解约 SPI（CH-6）");
    }

    /**
     * 渠道侧代扣动作（凭协议扣款）。<b>默认委托 {@link #pay(ChannelPayRequest)}</b>：
     * 平台侧代扣不新造资金路径，经 {@code PaymentFacade.pay(method=CARD)} → TCC Try →
     * 本 SPI 受理 → Confirm 扣款，复式记账/幂等/风控/手续费全部沿用既有链路。
     * 真实渠道若需独立「委托扣款」报文，只覆写本方法即可（开闭原则）。
     */
    default ChannelResult agentPay(ChannelPayRequest request) {
        return pay(request);
    }
}