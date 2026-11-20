package com.qoder.payment.facade.channel;

import com.qoder.payment.common.result.Result;

import java.util.List;

/**
 * 渠道网关：模拟银行渠道，TCC 预授权/扣款/撤销。对标支付宝渠道网关。
 *
 * <p>CH-1 增量：查询 SPI（{@link #queryChannelTrade}）+ 终态回执入口
 * （{@link #receiveReceipt}），支撑支付侧 PROCESSING 收敛补单与异步渠道二阶段。
 * 同步渠道（模拟银行默认）行为不变。
 */
public interface ChannelFacade {

    Result<List<ChannelTradeDTO>> listTrades();

    /**
     * 渠道交易查询 SPI（只读，返回当前 CAS 状态）：供支付侧补单判定渠道终态。
     * 不存在时返回 A0404，调用方不得据此臆断失败。
     */
    Result<ChannelTradeDTO> queryChannelTrade(String bizNo);

    /**
     * 接收渠道终态回执（幂等）：回执落 channel_receipt_log 去重 + channel_trade 状态 CAS，
     * 终态只翻一次，重复/乱序回执被吸收。返回回执处理后的渠道交易快照。
     *
     * <p>本入口只负责渠道侧终态落地，不反向调用支付域（避免域间环依赖）；
     * 支付单由 PaymentReconcileJob 查询收敛或手动 reconverge 立即收敛。
     */
    Result<ChannelTradeDTO> receiveReceipt(ChannelReceiptRequest request);

    // ---------- CH-2a SPI 可插拔观测 ----------

    /** 渠道配置列表（channel_config：适配器类型/环境/状态/成本/限额/维护窗口）。 */
    Result<List<ChannelConfigDTO>> listChannels();

    /** 已注册 SPI 适配器列表（新增渠道只需实现 ChannelAdapter + 注册，开闭原则）。 */
    Result<List<ChannelAdapterDTO>> listAdapters();

    /**
     * 渠道侧查询（SPI {@code ChannelAdapter#query}，只读）：返回渠道方视角的状态视图。
     * 与 {@link #queryChannelTrade}（本地 CAS 快照，支付补单决策依据）区分：
     * 本方法不改写任何本地状态，供补单 job / 运维核对渠道侧终态。
     */
    Result<ChannelTradeDTO> queryChannelSide(String bizNo);

    // ---------- CH-3 路由择优 + 故障切换 ----------

    /** 路由决策（预览与受理同一口径）：硬过滤（限额/维护/熔断/异步开关）+ 加权评分择优。 */
    Result<RouteResultDTO> route(ChannelRouteRequest request);

    /** 各渠道滑动窗口成功率/耗时/熔断态（路由因子可观测）。 */
    Result<List<ChannelMetricsDTO>> channelMetrics();

    // ---------- CH-5 限额 / 维护窗口 ----------

    /** 渠道限额与当日累计视图（生效值 DynamicConfig 优先于 channel_config）。 */
    Result<ChannelLimitDTO> getChannelLimit(String channelCode);

    /** 设置渠道单笔/日限额（分，null=不限制）；受理前生效，超限拒绝不留悬挂单。 */
    Result<ChannelLimitDTO> setChannelLimit(String channelCode, Long singleLimit, Long dailyLimit);

    /** 设置维护窗口（epoch 毫秒，start=end=0 表示取消）；窗口内路由排除该渠道。 */
    Result<ChannelLimitDTO> setMaintenance(String channelCode, Long start, Long end);

    // ---------- CH-4 对账文件下载 / 解析 / 逐笔比对 ----------

    /** 下载 + 解析 + 逐笔比对（幂等：UNIQUE(channel_code,biz_date)），差异经 DisputeFacade 建差错单。 */
    Result<ChannelBillDTO> downloadBill(String channelCode, String bizDate);

    /** 账单列表（channelCode/bizDate 均可空=全量）。 */
    Result<List<ChannelBillDTO>> listBills(String channelCode, String bizDate);

    /** 账单详情（含明细与差异）。 */
    Result<ChannelBillDTO> getBillDetail(long billId);

    /** 重新逐笔比对（幂等：差错单按 (DISPUTE, PAYMENT:CHANNEL_BILL:bizDate:bizNo) 去重）。 */
    Result<ChannelBillDTO> reconcileBill(long billId);
}