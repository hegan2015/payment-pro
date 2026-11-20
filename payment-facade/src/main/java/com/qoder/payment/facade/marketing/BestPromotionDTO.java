package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * C-4 最优组合结果（<b>用户最优 = 实付最小</b>，可解释 + 确定性 + 不超预算 + 不违反互斥）。
 *
 * <p><b>算法（精确 + 有界剪枝）</b>：
 * <ol>
 *   <li>各来源候选额相互独立（活动立减按<b>订单全额</b>算比例/满减，券/红包为面额，积分为可用额/兑换率），
 *       且券/红包/积分<b>可部分使用</b>（截断），活动<b>不可截断</b>；</li>
 *   <li>因此给定可行集合 S，最大总抵扣 = min(Σ原始额(S), cap)，其中 cap = 订单额 × maxDiscountBps/10000。
 *       min(·,cap) 对 Σ 单调 ⇒ <b>最大化 Σ原始额即最优</b>，再按确定性顺序截断到 cap；</li>
 *   <li>可截断组（券/红包/积分）在张数上限内取<b>面额降序前 k</b>即为该组最优（值正且独立，交换论证）；</li>
 *   <li>不可截断组（活动）需满足 Σ ≤ cap，用<b>值降序分支限界</b>求最大可行子集（节点预算有界）；</li>
 *   <li>来源组开关组合仅 2^4=16 种，逐一过互斥过滤后取最优；平局按
 *       (总抵扣降序, Σ原始额降序, 选中项数升序, 组掩码序升序) 确定性裁决。</li>
 * </ol>
 *
 * <p><b>剪枝无损性</b>：每来源候选超过 {@code maxCandidatesPerSource} 时按面额降序保留前 N——
 * 因组内最优只取前 k（k ≤ 叠加上限 ≤ N），被剪掉的候选<b>不可能进入任何最优解</b>，故剪枝不损失最优性，
 * 只在 {@code prunedCandidates} 与落选原因 {@code PRUNED} 中留痕。活动子集搜索超节点预算时
 * {@code exhaustive=false}（此时给出的是已证可行下界，仍绝不超预算/违反互斥）。
 *
 * <p><b>资金红线</b>：本结果只是建议，<b>不核销不动账</b>；{@code payAmount = orderAmount − totalDiscount ≥ 0}。
 * 手续费口径不受影响（fee 仍按订单全额由 settle FeeCalculator 计算）。
 */
@Data
public class BestPromotionDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String memberId;
    private String orderId;
    /** 订单金额（分）。 */
    private Long orderAmount;
    /** 最优总抵扣（分）= Σ选中 amount。 */
    private Long totalDiscount;
    /** 最优实付（分）= orderAmount − totalDiscount，恒 ≥ 0。 */
    private Long payAmount;
    private Long couponAmount;
    private Long redPacketAmount;
    private Long pointsAmount;
    private Long activityAmount;
    /** 建议消耗积分数（POINTS 选中项）。 */
    private Long pointsUsed;
    /** 生效积分兑换率（多少积分抵 1 分，受理快照口径）。 */
    private Long pointsExchangeRate;
    private List<PromotionPickDTO> selected;
    private List<PromotionRejectDTO> rejected;
    /** 生效规则回显（含缺省填充），便于复算与解释。 */
    private PromotionRulesDTO rules;
    /** 参与计算的候选总数（剪枝后）。 */
    private Integer candidateCount;
    /** 实际评估的来源组组合数（≤16）。 */
    private Integer evaluatedCombinations;
    /** 无损剪枝掉的候选数。 */
    private Integer prunedCandidates;
    /** 活动子集分支限界实际展开节点数。 */
    private Integer searchNodes;
    /** 是否在节点预算内穷尽（true=最优性已证；false=有界近似，仍可行）。 */
    private Boolean exhaustive;
    /** 所有选中活动均满足预算余额（不超预算）。 */
    private Boolean budgetRespected;
    /** 选中集合未违反任何互斥/叠加规则。 */
    private Boolean mutexRespected;
    /** 算法说明（确定性文案）。 */
    private String strategy;
    /** 选中理由与关键约束汇总（人类可读）。 */
    private String explanation;
    /** 输入指纹（候选 id+额+门槛+预算余额+规则 的有序摘要）：同输入同输出可复核。 */
    private String inputFingerprint;
    private Long createdAt;
}