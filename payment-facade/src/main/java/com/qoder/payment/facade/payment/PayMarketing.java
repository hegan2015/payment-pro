package com.qoder.payment.facade.payment;

import com.qoder.payment.facade.marketing.AllocTargetDTO;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * P-7 营销抵扣诉求（<b>PayRequest 的 additive 可选字段</b>，缺省 null=完全短路）。
 *
 * <p><b>ride 兼容红线（最高）</b>：本字段缺省 null，{@code MarketingPayService.requested()} 是
 * <b>纯内存判定</b>——无营销诉求时零 Dubbo 调用、零额外延迟、fee 口径与 BALANCE 同步语义逐字节不变。
 *
 * <p><b>抵扣模型（整单抵扣，择一并说明守恒）</b>：营销补贴先经既有 {@code AccountingFacade.post}
 * 落 {@code D SYS_MARKETING / C 用户余额户}（P2-A 既定口径），随后资金腿按<b>订单全额</b>扣款。
 * 于是：
 * <ul>
 *   <li>用户<b>净实付</b> = 订单额 − Σ抵扣（{@code payAmount}，恒 ≥ 0）；</li>
 *   <li>商户实收 = 订单额 − fee（<b>fee 基数仍是订单全额，口径一字不改</b>）；</li>
 *   <li>SYS_MARKETING 净出账 = Σ抵扣；Σbalance=0 每币种/整体成立；</li>
 *   <li>守恒恒等式：{@code orderAmount = payAmount + marketingAmount}，
 *       且 {@code marketingAmount = Σ来源核销净额 = Σ目标分摊净额}（diff=0）。</li>
 * </ul>
 * COMBO 衔接：Σ资金腿仍 == 订单额（组合受理强校验不变），补贴入其中一条 BALANCE 腿的付款户，
 * 该腿余额校验延后到补贴到账之后；其余腿校验不变。
 *
 * <p><b>编排顺序</b>：券 → 红包 → 积分 → 活动，每步幂等（营销域既有幂等键不变），
 * 任一步失败按<b>逆序退回已核销权益</b>（资金反向 + 券/红包/积分/预算回补），整体不落半成品。
 */
@Data
public class PayMarketing implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 权益归属会员（必填）。 */
    private String memberId;
    /**
     * 抵扣入账户（可选）：缺省=付款 BALANCE 户。
     * <b>必须等于付款 BALANCE 户</b>（单腿）或某条 BALANCE 腿付款户（COMBO），否则拒绝——
     * 补贴不入付款户则「净实付=订单额−Σ抵扣」不成立。
     */
    private String accountId;
    /** 指定券 id 列表（按入参顺序核销）。 */
    private List<String> couponIds;
    /** 指定红包 id 列表。 */
    private List<String> redPacketIds;
    /** 指定消耗积分数（按受理时兑换率换算成分，向下取整）。 */
    private Long points;
    /** 指定活动 id 列表。 */
    private List<String> activityIds;
    /** true=调 C-4 最优组合自动选（实付最小），此时忽略上面四个显式列表。 */
    private Boolean auto;
    /** 自动选时的规则覆盖（透传 C-4）。 */
    private com.qoder.payment.facade.marketing.PromotionRulesDTO rules;
    /** 目标维度分摊目标（可选，缺省单 ORDER 目标=orderId，权重 1）。 */
    private List<AllocTargetDTO> allocTargets;
    /** 分摊模式 WEIGHT（缺省）/ EXPLICIT。 */
    private String allocMode;

    /** 是否有任一营销诉求（纯内存判定，零 RPC；ride 无权益时恒 false）。 */
    public boolean hasAny() {
        return Boolean.TRUE.equals(auto)
                || (couponIds != null && !couponIds.isEmpty())
                || (redPacketIds != null && !redPacketIds.isEmpty())
                || (activityIds != null && !activityIds.isEmpty())
                || (points != null && points > 0);
    }
}