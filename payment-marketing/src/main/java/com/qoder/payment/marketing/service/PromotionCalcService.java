package com.qoder.payment.marketing.service;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.facade.marketing.ActivityDTO;
import com.qoder.payment.facade.marketing.BestPromotionDTO;
import com.qoder.payment.facade.marketing.BestPromotionRequest;
import com.qoder.payment.facade.marketing.CouponDTO;
import com.qoder.payment.facade.marketing.PointsAccountDTO;
import com.qoder.payment.facade.marketing.PromotionPickDTO;
import com.qoder.payment.facade.marketing.PromotionRejectDTO;
import com.qoder.payment.facade.marketing.PromotionRulesDTO;
import com.qoder.payment.facade.marketing.RedPacketDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C-4 营销计算：可用优惠聚合 + <b>用户最优（实付最小）组合</b>，可解释 / 确定性 / 有界剪枝。
 *
 * <p><b>只读</b>：不核销、不动账、不写任何单据（核销在 P-7 支付受理编排）。手续费口径不参与——
 * fee 仍由 settle FeeCalculator 按订单全额计算，本服务只算「抵扣额」与「净实付」。
 *
 * <p><b>算法与最优性证明</b>：
 * <ol>
 *   <li>候选额相互独立：活动立减按<b>订单全额</b>算（满减固定额 / 比例×封顶），券/红包为面额，
 *       积分为可用额÷兑换率；不存在「先抵 A 再按余额算 B」的耦合。</li>
 *   <li>券/红包/积分<b>可部分使用</b>（营销域核销支持 amount ≤ 面额 / 积分数任选），活动<b>不可截断</b>。
 *       故给定可行集合 S，可达最大总抵扣 = {@code min(Σ原始额(S), cap)}，
 *       其中 {@code cap = orderAmount × maxDiscountBps / 10000}。</li>
 *   <li>{@code min(·, cap)} 对 Σ 单调不减 ⇒ <b>最大化 Σ原始额即得最优</b>，随后按确定性顺序截断到 cap。</li>
 *   <li>可截断组在张数上限 k 内取「面额降序前 k」即该组最优（值正且独立，交换论证：
 *       用组内更大面额替换更小面额不会降低 Σ，也不违反任何约束）。</li>
 *   <li>不可截断组（活动）须 Σ ≤ cap，用<b>值降序 + 后缀和上界的分支限界</b>求最大可行子集，
 *       节点预算有界（超预算标 {@code exhaustive=false}，返回已证可行解，绝不超预算/违反互斥）。</li>
 *   <li>来源组开关组合仅 2^4 = 16 种，过互斥后逐一评估，取
 *       (score 降序, Σ原始额降序, 选中项数升序, 掩码升序) 的确定性最优。</li>
 * </ol>
 *
 * <p><b>剪枝无损性</b>：每来源候选超过 {@code maxCandidatesPerSource}（默认 200）时按面额降序保留前 N。
 * 因组内最优只取前 k（k ≤ 叠加上限 ≤ N），被剪候选<b>不可能出现在任何最优解</b>中 ⇒ 剪枝不损失最优性，
 * 仅在 {@code prunedCandidates} 与落选码 {@code PRUNED} 留痕。这就是「候选爆炸时的性能上限」：
 * 总复杂度 O(16 × (N log N + 活动分支限界节点预算))，与候选总数线性相关，绝不指数爆炸。
 *
 * <p><b>确定性</b>：所有排序显式带 refId 次序键，所有平局有确定性裁决，配置读取一次快照；
 * {@code inputFingerprint} = 候选(id/额/门槛/预算余额) + 规则 + 订单额 + 兑换率 的 SHA-256，
 * 同输入必同输出（可复核）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionCalcService {

    public static final String SRC_COUPON = "COUPON";
    public static final String SRC_RED_PACKET = "RED_PACKET";
    public static final String SRC_POINTS = "POINTS";
    public static final String SRC_ACTIVITY = "ACTIVITY";
    /** 掩码位序（固定，保证确定性）。 */
    private static final List<String> SOURCES = List.of(SRC_COUPON, SRC_RED_PACKET, SRC_POINTS, SRC_ACTIVITY);
    private static final int BIT_COUPON = 1;
    private static final int BIT_RED_PACKET = 2;
    private static final int BIT_POINTS = 4;
    private static final int BIT_ACTIVITY = 8;
    private static final long MAX_AMOUNT = 1_000_000_000_000L;

    private final CouponService couponService;
    private final RedPacketService redPacketService;
    private final ActivityService activityService;
    private final PointsService pointsService;
    private final MarketingSupport support;
    private final ObjectProvider<DynamicConfig> dynamicConfig;

    /** 候选（组内按面额降序 + refId 升序排名）。 */
    private static final class Cand {
        String source;
        String refId;
        String name;
        long value;
        boolean truncatable;
        long threshold;
        /** 活动预算余额；-1 表示不限量。 */
        long budgetRemaining = -1L;
        int rank;
        String detail;

        String key() {
            return source + ":" + refId;
        }
    }

    /** 活动子集分支限界结果。 */
    private static final class ActResult {
        List<Cand> chosen = new ArrayList<>();
        long sum;
        int nodes;
        boolean exhaustive = true;
    }

    public BestPromotionDTO calc(BestPromotionRequest request) {
        MarketingSupport.require(request != null, "请求必填");
        MarketingSupport.require(MarketingSupport.notBlank(request.getMemberId()), "memberId 必填");
        MarketingSupport.require(request.getOrderAmount() != null && request.getOrderAmount() > 0
                && request.getOrderAmount() <= MAX_AMOUNT, "orderAmount 需在 (0,1e12] 分内");
        long orderAmount = request.getOrderAmount();
        String memberId = request.getMemberId();
        PromotionRulesDTO rules = resolveRules(request.getRules());
        long rate = support.pointsRate();
        long cap = orderAmount * MarketingSupport.nz(rules.getMaxDiscountBps()) / 10000L;
        int maxCand = clamp(request.getMaxCandidatesPerSource() != null ? request.getMaxCandidatesPerSource()
                : cfgInt("marketing.promo.max-candidates-per-source", 200), 1, 5000);
        int nodeBudget = clamp(request.getMaxSearchNodes() != null ? request.getMaxSearchNodes()
                : cfgInt("marketing.promo.max-search-nodes", 20000), 1, 1_000_000);

        List<PromotionRejectDTO> rejected = new ArrayList<>();
        Map<String, List<Cand>> groups = buildCandidates(memberId, orderAmount, rate, rules, request, rejected);

        // 无损剪枝：组内只保留面额降序前 N（N ≥ 叠加上限 ⇒ 被剪者不可能进最优解）
        int pruned = 0;
        for (String source : SOURCES) {
            List<Cand> list = groups.get(source);
            list.sort(Comparator.comparingLong((Cand c) -> c.value).reversed().thenComparing(c -> c.refId));
            for (int i = 0; i < list.size(); i++) {
                list.get(i).rank = i;
            }
            int limit = stackLimit(rules, source);
            if (list.size() > maxCand) {
                for (int i = maxCand; i < list.size(); i++) {
                    Cand c = list.get(i);
                    rejected.add(reject(c.source, c.refId, c.value, "PRUNED",
                            "候选爆炸无损剪枝：组内面额排名第 " + (i + 1) + " 超出候选上限 " + maxCand
                                    + "（叠加上限 " + limit + " ≤ 上限，被剪者不可能进入最优解）"));
                }
                pruned += list.size() - maxCand;
                groups.put(source, new ArrayList<>(list.subList(0, maxCand)));
            }
        }

        // 枚举 16 种来源组开关组合，过互斥后取最优
        int bestMask = 0;
        long bestScore = 0L;
        long bestRaw = 0L;
        int bestCount = 0;
        ActResult bestAct = new ActResult();
        List<Cand> bestTrunc = new ArrayList<>();
        int evaluated = 0;
        int totalNodes = 0;
        boolean exhaustive = true;
        for (int mask = 0; mask < 16; mask++) {
            if (!mutexFeasible(mask, rules)) {
                continue;
            }
            boolean empty = false;
            for (int b = 0; b < 4; b++) {
                if ((mask & (1 << b)) != 0 && groups.get(SOURCES.get(b)).isEmpty()) {
                    empty = true;
                    break;
                }
            }
            if (empty) {
                continue;
            }
            evaluated++;
            ActResult act = new ActResult();
            if ((mask & BIT_ACTIVITY) != 0) {
                act = bestActivitySubset(groups.get(SRC_ACTIVITY), stackLimit(rules, SRC_ACTIVITY), cap, nodeBudget);
                totalNodes += act.nodes;
                exhaustive &= act.exhaustive;
            }
            List<Cand> trunc = new ArrayList<>();
            long truncSum = 0L;
            for (String source : List.of(SRC_COUPON, SRC_RED_PACKET, SRC_POINTS)) {
                if ((mask & bit(source)) == 0) {
                    continue;
                }
                List<Cand> list = groups.get(source);
                int k = Math.min(stackLimit(rules, source), list.size());
                for (int i = 0; i < k; i++) {
                    trunc.add(list.get(i));
                    truncSum += list.get(i).value;
                }
            }
            long raw = act.sum + truncSum;
            long score = Math.min(raw, cap);
            int count = act.chosen.size() + trunc.size();
            if (better(score, raw, count, mask, bestScore, bestRaw, bestCount, bestMask)) {
                bestScore = score;
                bestRaw = raw;
                bestCount = count;
                bestMask = mask;
                bestAct = act;
                bestTrunc = trunc;
            }
        }

        // 截断到 cap（确定性顺序：积分 → 红包（排名逆序）→ 券（排名逆序）；活动不可截断）
        // 注意：绝不改写 Cand.value —— 原始额是「输入指纹」与截断留痕的事实来源，只用 amounts 映射承载终额。
        List<Cand> ordered = new ArrayList<>(bestTrunc);
        ordered.sort(Comparator.comparingInt((Cand c) -> truncOrder(c.source)).thenComparingInt(c -> -c.rank));
        long excess = bestRaw - bestScore;
        Map<String, Long> amounts = new LinkedHashMap<>();
        Set<String> zeroed = new LinkedHashSet<>();
        for (Cand c : ordered) {
            long value = c.value;
            if (excess > 0) {
                long cut = Math.min(excess, value);
                value -= cut;
                excess -= cut;
                if (value == 0) {
                    zeroed.add(c.key());
                }
            }
            amounts.put(c.key(), value);
        }
        List<Cand> selected = new ArrayList<>();
        for (Cand c : ordered) {
            if (amounts.getOrDefault(c.key(), 0L) > 0) {
                selected.add(c);
            }
        }
        selected.addAll(bestAct.chosen);
        selected.sort(Comparator.comparingInt((Cand c) -> consumptionOrder(c.source)).thenComparingInt(c -> c.rank));

        BestPromotionDTO dto = assemble(request, orderAmount, rate, cap, rules, groups, selected, amounts, zeroed,
                bestMask, bestScore, rejected, pruned, evaluated, totalNodes, exhaustive, maxCand, nodeBudget);
        log.info("C-4 最优组合 member={} orderAmount={} cap={} candidates={} pruned={} masks={} nodes={} "
                        + "discount={} payAmount={} exhaustive={} fingerprint={}",
                memberId, orderAmount, cap, dto.getCandidateCount(), pruned, evaluated, totalNodes,
                dto.getTotalDiscount(), dto.getPayAmount(), exhaustive, dto.getInputFingerprint());
        return dto;
    }

    // ==================== 候选聚合（含落选原因） ====================

    private Map<String, List<Cand>> buildCandidates(String memberId, long orderAmount, long rate,
                                                    PromotionRulesDTO rules, BestPromotionRequest request,
                                                    List<PromotionRejectDTO> rejected) {
        Map<String, List<Cand>> groups = new LinkedHashMap<>();
        for (String source : SOURCES) {
            groups.put(source, new ArrayList<>());
        }
        long now = System.currentTimeMillis();

        // ---- 券（门槛/过期/状态自校验，落选可解释） ----
        if (stackLimit(rules, SRC_COUPON) <= 0) {
            for (CouponDTO c : couponService.list(memberId, CouponService.STATUS_AVAILABLE)) {
                rejected.add(reject(SRC_COUPON, c.getId(), MarketingSupport.nz(c.getAmount()), "DISABLED",
                        "规则关闭券叠加（maxCoupons=0）"));
            }
        } else {
            for (CouponDTO c : couponService.list(memberId, CouponService.STATUS_AVAILABLE)) {
                if (!inFilter(request.getCouponIds(), c.getId())) {
                    continue;
                }
                long face = MarketingSupport.nz(c.getAmount());
                if (c.getExpireAt() != null && c.getExpireAt() <= now) {
                    rejected.add(reject(SRC_COUPON, c.getId(), face, "EXPIRED", "券已过期 expireAt=" + c.getExpireAt()));
                    continue;
                }
                long threshold = MarketingSupport.nz(c.getThreshold());
                if (orderAmount < threshold) {
                    rejected.add(reject(SRC_COUPON, c.getId(), face, "THRESHOLD_NOT_MET",
                            "未达满减门槛：订单额 " + orderAmount + " < 门槛 " + threshold
                                    + "（差 " + (threshold - orderAmount) + " 分）"));
                    continue;
                }
                if (face <= 0) {
                    rejected.add(reject(SRC_COUPON, c.getId(), face, "ZERO_DISCOUNT", "券面额为 0"));
                    continue;
                }
                Cand cand = new Cand();
                cand.source = SRC_COUPON;
                cand.refId = c.getId();
                cand.name = "优惠券";
                cand.value = face;
                cand.truncatable = true;
                cand.threshold = threshold;
                cand.detail = "面额 " + face + " 分，门槛 " + threshold + " 分（订单额 " + orderAmount + " 已满足）";
                groups.get(SRC_COUPON).add(cand);
            }
        }

        // ---- 红包（无门槛现金券） ----
        if (stackLimit(rules, SRC_RED_PACKET) <= 0) {
            for (RedPacketDTO p : redPacketService.list(memberId, CouponService.STATUS_AVAILABLE)) {
                rejected.add(reject(SRC_RED_PACKET, p.getId(), MarketingSupport.nz(p.getAmount()), "DISABLED",
                        "规则关闭红包叠加（maxRedPackets=0）"));
            }
        } else {
            for (RedPacketDTO p : redPacketService.list(memberId, CouponService.STATUS_AVAILABLE)) {
                if (!inFilter(request.getRedPacketIds(), p.getId())) {
                    continue;
                }
                long face = MarketingSupport.nz(p.getAmount());
                if (p.getExpireAt() != null && p.getExpireAt() <= now) {
                    rejected.add(reject(SRC_RED_PACKET, p.getId(), face, "EXPIRED", "红包已过期 expireAt=" + p.getExpireAt()));
                    continue;
                }
                if (face <= 0) {
                    rejected.add(reject(SRC_RED_PACKET, p.getId(), face, "ZERO_DISCOUNT", "红包面额为 0"));
                    continue;
                }
                Cand cand = new Cand();
                cand.source = SRC_RED_PACKET;
                cand.refId = p.getId();
                cand.name = "红包";
                cand.value = face;
                cand.truncatable = true;
                cand.detail = "无门槛现金红包，面额 " + face + " 分";
                groups.get(SRC_RED_PACKET).add(cand);
            }
        }

        // ---- 积分（虚拟权益 → 金额，兑换率快照） ----
        PointsAccountDTO points = pointsService.account(memberId);
        long available = MarketingSupport.nz(points.getAvailable());
        long pointsValue = available / rate;
        if (!Boolean.FALSE.equals(request.getUsePoints()) && pointsValue > 0) {
            Cand cand = new Cand();
            cand.source = SRC_POINTS;
            cand.refId = memberId;
            cand.name = "积分抵扣";
            cand.value = pointsValue;
            cand.truncatable = true;
            cand.detail = "可用积分 " + available + "（余额 " + MarketingSupport.nz(points.getBalance())
                    + " − 冻结 " + MarketingSupport.nz(points.getFrozen()) + "），兑换率 " + rate
                    + " 积分/分 ⇒ 可抵 " + pointsValue + " 分";
            groups.get(SRC_POINTS).add(cand);
        } else if (Boolean.FALSE.equals(request.getUsePoints())) {
            rejected.add(reject(SRC_POINTS, memberId, pointsValue, "DISABLED", "请求关闭积分抵扣（usePoints=false）"));
        } else {
            rejected.add(reject(SRC_POINTS, memberId, pointsValue, "POINTS_ZERO",
                    "可用积分 " + available + " 不足抵 1 分（兑换率 " + rate + " 积分/分）"));
        }

        // ---- 活动（门槛/比例/封顶/预算余额） ----
        for (ActivityDTO a : activityService.list(null)) {
            if (!inFilter(request.getActivityIds(), a.getId())) {
                continue;
            }
            long threshold = MarketingSupport.nz(a.getThreshold());
            long value = activityService.discountOf(a, orderAmount);
            if (orderAmount < threshold) {
                rejected.add(reject(SRC_ACTIVITY, a.getId(), value, "THRESHOLD_NOT_MET",
                        "未达活动门槛：订单额 " + orderAmount + " < 门槛 " + threshold));
                continue;
            }
            if (value <= 0) {
                rejected.add(reject(SRC_ACTIVITY, a.getId(), value, "ZERO_DISCOUNT", "活动立减额为 0"));
                continue;
            }
            long budget = MarketingSupport.nz(a.getBudget());
            long remaining = budget <= 0 ? -1L : budget - MarketingSupport.nz(a.getUsed());
            if (remaining >= 0 && remaining < value) {
                rejected.add(reject(SRC_ACTIVITY, a.getId(), value, "BUDGET_INSUFFICIENT",
                        "活动预算余额不足：余额 " + remaining + " < 立减额 " + value + "（绝不为凑最优超预算）"));
                continue;
            }
            if (stackLimit(rules, SRC_ACTIVITY) <= 0) {
                rejected.add(reject(SRC_ACTIVITY, a.getId(), value, "DISABLED", "规则关闭活动叠加（maxActivities=0）"));
                continue;
            }
            Cand cand = new Cand();
            cand.source = SRC_ACTIVITY;
            cand.refId = a.getId();
            cand.name = a.getName();
            cand.value = value;
            cand.truncatable = false;
            cand.threshold = threshold;
            cand.budgetRemaining = remaining;
            cand.detail = ActivityService.TYPE_FULL_REDUCE.equals(a.getType())
                    ? "满减：满 " + threshold + " 减 " + MarketingSupport.nz(a.getDiscountAmount()) + " 分"
                    : "比例立减 " + MarketingSupport.nz(a.getDiscountBps()) + "bps（订单全额计）"
                    + (MarketingSupport.nz(a.getMaxDiscount()) > 0
                    ? "，封顶 " + a.getMaxDiscount() + " 分" : "，不封顶")
                    + "，预算余额 " + (remaining < 0 ? "不限量" : remaining + " 分");
            groups.get(SRC_ACTIVITY).add(cand);
        }
        return groups;
    }

    // ==================== 活动子集：值降序 + 后缀和上界分支限界 ====================

    private ActResult bestActivitySubset(List<Cand> acts, int limit, long cap, int nodeBudget) {
        ActResult result = new ActResult();
        if (acts.isEmpty() || limit <= 0 || cap <= 0) {
            return result;
        }
        List<Cand> list = new ArrayList<>(acts);
        list.sort(Comparator.comparingLong((Cand c) -> c.value).reversed().thenComparing(c -> c.refId));
        long[] suffix = new long[list.size() + 1];
        for (int i = list.size() - 1; i >= 0; i--) {
            suffix[i] = suffix[i + 1] + list.get(i).value;
        }
        int[] nodes = {0};
        boolean[] exhausted = {false};
        List<Cand> path = new ArrayList<>();
        dfs(list, suffix, 0, 0L, path, limit, cap, nodeBudget, nodes, exhausted, result);
        result.nodes = nodes[0];
        result.exhaustive = !exhausted[0];
        return result;
    }

    private void dfs(List<Cand> list, long[] suffix, int idx, long sum, List<Cand> path,
                     int limit, long cap, int nodeBudget, int[] nodes, boolean[] exhausted, ActResult best) {
        if (exhausted[0]) {
            return;
        }
        if (++nodes[0] > nodeBudget) {
            exhausted[0] = true;
            return;
        }
        if (sum > best.sum) {
            best.sum = sum;
            best.chosen = new ArrayList<>(path);
        }
        if (idx >= list.size() || path.size() >= limit) {
            return;
        }
        // 上界剪枝：即便把剩余全选也追不上当前最优 ⇒ 整枝丢弃（确定性、不损失最优性）
        if (sum + suffix[idx] <= best.sum) {
            return;
        }
        for (int i = idx; i < list.size(); i++) {
            Cand c = list.get(i);
            if (sum + c.value > cap) {
                continue;
            }
            if (sum + suffix[i] <= best.sum) {
                break;
            }
            path.add(c);
            dfs(list, suffix, i + 1, sum + c.value, path, limit, cap, nodeBudget, nodes, exhausted, best);
            path.remove(path.size() - 1);
            if (exhausted[0]) {
                return;
            }
        }
    }

    // ==================== 结果装配（可解释 + 确定性指纹） ====================

    private BestPromotionDTO assemble(BestPromotionRequest request, long orderAmount, long rate, long cap,
                                      PromotionRulesDTO rules, Map<String, List<Cand>> groups, List<Cand> selected,
                                      Map<String, Long> amounts, Set<String> zeroed, int bestMask, long bestScore,
                                      List<PromotionRejectDTO> rejected, int pruned, int evaluated, int nodes,
                                      boolean exhaustive, int maxCand, int nodeBudget) {
        long couponAmount = 0L;
        long redPacketAmount = 0L;
        long pointsAmount = 0L;
        long activityAmount = 0L;
        long pointsUsed = 0L;
        List<PromotionPickDTO> picks = new ArrayList<>();
        Set<String> selectedKeys = new LinkedHashSet<>();
        for (Cand c : selected) {
            long amount = amounts.getOrDefault(c.key(), c.value);
            PromotionPickDTO pick = new PromotionPickDTO();
            pick.setSource(c.source);
            pick.setRefId(c.refId);
            pick.setName(c.name);
            pick.setAmount(amount);
            pick.setOriginalAmount(c.value);
            pick.setTruncated(c.value > amount);
            pick.setReason(c.detail + "；组内面额排名第 " + (c.rank + 1) + "，叠加上限 "
                    + stackLimit(rules, c.source) + " 内入选"
                    + (Boolean.TRUE.equals(pick.getTruncated())
                    ? "；因总抵扣上限 " + cap + " 分被截断 " + (c.value - amount) + " 分（可部分使用）" : ""));
            if (SRC_POINTS.equals(c.source)) {
                pick.setPoints(amount * rate);
                pointsAmount += amount;
                pointsUsed += amount * rate;
            } else if (SRC_COUPON.equals(c.source)) {
                couponAmount += amount;
            } else if (SRC_RED_PACKET.equals(c.source)) {
                redPacketAmount += amount;
            } else {
                activityAmount += amount;
            }
            picks.add(pick);
            selectedKeys.add(c.key());
        }

        // 落选原因（未入选候选逐项给码）
        int actChosen = 0;
        for (Cand c : selected) {
            if (SRC_ACTIVITY.equals(c.source)) {
                actChosen++;
            }
        }
        for (String source : SOURCES) {
            for (Cand c : groups.get(source)) {
                if (selectedKeys.contains(c.key())) {
                    continue;
                }
                if (zeroed.contains(c.key())) {
                    rejected.add(reject(source, c.refId, c.value, "CAP_REACHED",
                            "总抵扣已达上限 " + cap + " 分，本项按确定性截断顺序归零落选"));
                    continue;
                }
                if ((bestMask & bit(source)) == 0) {
                    String conflict = mutexConflict(source, bestMask, rules);
                    if (conflict != null) {
                        rejected.add(reject(source, c.refId, c.value, "MUTEX",
                                "与选中来源 " + conflict + " 互斥（规则 " + mutexText(rules) + "）"));
                    } else if (bestScore >= cap) {
                        rejected.add(reject(source, c.refId, c.value, "CAP_REACHED",
                                "总抵扣已达上限 " + cap + " 分（实付已最小），追加本项不增抵扣"));
                    } else {
                        rejected.add(reject(source, c.refId, c.value, "NOT_OPTIMAL",
                                "加入该来源不增总抵扣，组合非最优"));
                    }
                    continue;
                }
                if (SRC_ACTIVITY.equals(source)) {
                    if (actChosen >= stackLimit(rules, SRC_ACTIVITY)) {
                        rejected.add(reject(source, c.refId, c.value, "STACK_LIMIT",
                                "活动叠加上限 " + stackLimit(rules, SRC_ACTIVITY) + " 已用满，本项立减额较小落选"));
                    } else {
                        rejected.add(reject(source, c.refId, c.value, "CAP_REACHED",
                                "活动立减不可部分使用，Σ活动立减须 ≤ 上限 " + cap + " 分，本项未入可行子集"));
                    }
                } else if (c.rank >= stackLimit(rules, source)) {
                    rejected.add(reject(source, c.refId, c.value, "STACK_LIMIT",
                            "超出叠加上限 " + stackLimit(rules, source) + "（组内面额排名第 " + (c.rank + 1)
                                    + "，取前 " + stackLimit(rules, source) + " 名）"));
                } else {
                    rejected.add(reject(source, c.refId, c.value, "NOT_OPTIMAL", "同组更优候选已入选"));
                }
            }
        }

        int candidateCount = 0;
        for (String source : SOURCES) {
            candidateCount += groups.get(source).size();
        }
        long total = couponAmount + redPacketAmount + pointsAmount + activityAmount;
        MarketingSupport.requireBiz(total == bestScore, "内部错误：最优组合金额不守恒 " + total + " ≠ " + bestScore);
        MarketingSupport.requireBiz(total <= cap && total <= orderAmount, "内部错误：抵扣超上限");

        BestPromotionDTO dto = new BestPromotionDTO();
        dto.setMemberId(request.getMemberId());
        dto.setOrderId(request.getOrderId());
        dto.setOrderAmount(orderAmount);
        dto.setTotalDiscount(total);
        dto.setPayAmount(orderAmount - total);
        dto.setCouponAmount(couponAmount);
        dto.setRedPacketAmount(redPacketAmount);
        dto.setPointsAmount(pointsAmount);
        dto.setActivityAmount(activityAmount);
        dto.setPointsUsed(pointsUsed);
        dto.setPointsExchangeRate(rate);
        dto.setSelected(picks);
        dto.setRejected(rejected);
        dto.setRules(rules);
        dto.setCandidateCount(candidateCount);
        dto.setEvaluatedCombinations(evaluated);
        dto.setPrunedCandidates(pruned);
        dto.setSearchNodes(nodes);
        dto.setExhaustive(exhaustive);
        dto.setBudgetRespected(budgetRespected(selected));
        dto.setMutexRespected(mutexFeasible(bestMask, rules) && stackRespected(selected, rules));
        dto.setStrategy("组掩码枚举(16) + 可截断组面额降序前k + 活动值降序分支限界(后缀和上界剪枝, 节点预算 "
                + nodeBudget + ") + 确定性截断(积分→红包→券)；候选上限 " + maxCand + "/来源（无损剪枝）");
        dto.setExplanation("订单额 " + orderAmount + " 分，抵扣上限 cap=" + cap + " 分（maxDiscountBps="
                + rules.getMaxDiscountBps() + "）；候选 " + candidateCount + " 项（无损剪枝 " + pruned
                + " 项），评估 " + evaluated + " 种来源组合，活动搜索 " + nodes + " 节点，exhaustive=" + exhaustive
                + "；最优总抵扣 " + total + " 分（券 " + couponAmount + " + 红包 " + redPacketAmount
                + " + 积分 " + pointsAmount + " + 活动 " + activityAmount + "），实付 " + (orderAmount - total)
                + " 分；互斥=" + mutexText(rules) + "，叠加=券" + rules.getMaxCoupons() + "/红包"
                + rules.getMaxRedPackets() + "/活动" + rules.getMaxActivities() + "/积分可叠加"
                + rules.getPointsStackable());
        dto.setInputFingerprint(fingerprint(request.getMemberId(), orderAmount, rate, cap, rules, groups));
        dto.setCreatedAt(System.currentTimeMillis());
        return dto;
    }

    private boolean budgetRespected(List<Cand> selected) {
        for (Cand c : selected) {
            if (SRC_ACTIVITY.equals(c.source) && c.budgetRemaining >= 0 && c.value > c.budgetRemaining) {
                return false;
            }
        }
        return true;
    }

    private boolean stackRespected(List<Cand> selected, PromotionRulesDTO rules) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Cand c : selected) {
            counts.merge(c.source, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > stackLimit(rules, e.getKey())) {
                return false;
            }
        }
        return true;
    }

    private String fingerprint(String memberId, long orderAmount, long rate, long cap,
                               PromotionRulesDTO rules, Map<String, List<Cand>> groups) {
        StringBuilder sb = new StringBuilder();
        sb.append(memberId).append('|').append(orderAmount).append('|').append(rate).append('|').append(cap)
                .append('|').append(rules.getMaxCoupons()).append(',').append(rules.getMaxRedPackets())
                .append(',').append(rules.getMaxActivities()).append(',').append(rules.getPointsStackable())
                .append(',').append(rules.getMaxDiscountBps()).append(',').append(mutexText(rules));
        for (String source : SOURCES) {
            List<Cand> list = new ArrayList<>(groups.get(source));
            list.sort(Comparator.comparing(c -> c.refId));
            for (Cand c : list) {
                sb.append('|').append(c.source).append(':').append(c.refId).append(':').append(c.value)
                        .append(':').append(c.threshold).append(':').append(c.budgetRemaining);
            }
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "SHA256-UNAVAILABLE";
        }
    }

    // ==================== 规则 / 互斥 / 排序辅助 ====================

    private PromotionRulesDTO resolveRules(PromotionRulesDTO override) {
        PromotionRulesDTO rules = new PromotionRulesDTO();
        rules.setMaxCoupons(clamp(pick(override == null ? null : override.getMaxCoupons(),
                cfgInt("marketing.promo.max-coupons", 1)), 0, 10));
        rules.setMaxRedPackets(clamp(pick(override == null ? null : override.getMaxRedPackets(),
                cfgInt("marketing.promo.max-red-packets", 1)), 0, 10));
        rules.setMaxActivities(clamp(pick(override == null ? null : override.getMaxActivities(),
                cfgInt("marketing.promo.max-activities", 1)), 0, 10));
        DynamicConfig cfg = dynamicConfig.getIfAvailable();
        boolean stackable = override != null && override.getPointsStackable() != null
                ? override.getPointsStackable()
                : cfg == null || cfg.getBool("marketing.promo.points-stackable", true);
        rules.setPointsStackable(stackable);
        List<String> mutex = override != null && override.getMutex() != null
                ? override.getMutex()
                : parseMutex(cfg == null ? "" : cfg.get("marketing.promo.mutex", ""));
        rules.setMutex(normalizeMutex(mutex));
        rules.setMaxDiscountBps(clamp(pick(override == null ? null : override.getMaxDiscountBps(),
                cfgInt("marketing.promo.max-discount-bps", 10000)), 0, 10000));
        return rules;
    }

    private static Integer pick(Integer override, int fallback) {
        return override != null ? override : fallback;
    }

    private int cfgInt(String key, int def) {
        DynamicConfig cfg = dynamicConfig.getIfAvailable();
        return cfg == null ? def : cfg.getInt(key, def);
    }

    private static List<String> parseMutex(String raw) {
        List<String> out = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split(",")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        }
        return out;
    }

    /** 归一化： uppercase + 按固定位序排序，去重；非法来源直接拒绝（绝不静默忽略互斥）。 */
    private static List<String> normalizeMutex(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String pair : raw) {
                if (pair == null || pair.isBlank()) {
                    continue;
                }
                String[] parts = pair.toUpperCase().split("\\|");
                MarketingSupport.require(parts.length == 2, "互斥规则须形如 COUPON|ACTIVITY: " + pair);
                String a = parts[0].trim();
                String b = parts[1].trim();
                MarketingSupport.require(SOURCES.contains(a) && SOURCES.contains(b),
                        "互斥规则来源非法（须 COUPON/RED_PACKET/POINTS/ACTIVITY）: " + pair);
                MarketingSupport.require(!a.equals(b), "互斥规则不得自互斥: " + pair);
                out.add(bit(a) < bit(b) ? a + "|" + b : b + "|" + a);
            }
        }
        return new ArrayList<>(out);
    }

    private static String mutexText(PromotionRulesDTO rules) {
        List<String> mutex = new ArrayList<>(rules.getMutex() == null ? List.of() : rules.getMutex());
        if (!Boolean.TRUE.equals(rules.getPointsStackable())) {
            for (String source : List.of(SRC_COUPON, SRC_RED_PACKET, SRC_ACTIVITY)) {
                mutex.add(SRC_POINTS + "|" + source);
            }
        }
        return mutex.isEmpty() ? "无" : String.join(",", mutex);
    }

    private static boolean mutexFeasible(int mask, PromotionRulesDTO rules) {
        if (rules.getMutex() != null) {
            for (String pair : rules.getMutex()) {
                String[] parts = pair.split("\\|");
                if ((mask & bit(parts[0])) != 0 && (mask & bit(parts[1])) != 0) {
                    return false;
                }
            }
        }
        if (!Boolean.TRUE.equals(rules.getPointsStackable()) && (mask & BIT_POINTS) != 0) {
            return (mask & ~BIT_POINTS) == 0;
        }
        return true;
    }

    /** 返回与 source 冲突的已选中来源（无冲突返回 null）。 */
    private static String mutexConflict(String source, int mask, PromotionRulesDTO rules) {
        if (rules.getMutex() != null) {
            for (String pair : rules.getMutex()) {
                String[] parts = pair.split("\\|");
                String other = parts[0].equals(source) ? parts[1] : parts[1].equals(source) ? parts[0] : null;
                if (other != null && (mask & bit(other)) != 0) {
                    return other;
                }
            }
        }
        if (!Boolean.TRUE.equals(rules.getPointsStackable())) {
            if (SRC_POINTS.equals(source) && (mask & ~BIT_POINTS) != 0) {
                for (String s : SOURCES) {
                    if (!SRC_POINTS.equals(s) && (mask & bit(s)) != 0) {
                        return s;
                    }
                }
            }
            if (!SRC_POINTS.equals(source) && (mask & BIT_POINTS) != 0) {
                return SRC_POINTS;
            }
        }
        return null;
    }

    private static int stackLimit(PromotionRulesDTO rules, String source) {
        return switch (source) {
            case SRC_COUPON -> MarketingSupport.nz(rules.getMaxCoupons());
            case SRC_RED_PACKET -> MarketingSupport.nz(rules.getMaxRedPackets());
            case SRC_ACTIVITY -> MarketingSupport.nz(rules.getMaxActivities());
            case SRC_POINTS -> 1;
            default -> 0;
        };
    }

    private static int bit(String source) {
        return switch (source) {
            case SRC_COUPON -> BIT_COUPON;
            case SRC_RED_PACKET -> BIT_RED_PACKET;
            case SRC_POINTS -> BIT_POINTS;
            case SRC_ACTIVITY -> BIT_ACTIVITY;
            default -> 0;
        };
    }

    /** 截断顺序（先牺牲积分，再红包，最后券；保留券面额完整便于用户感知）。 */
    private static int truncOrder(String source) {
        return switch (source) {
            case SRC_POINTS -> 0;
            case SRC_RED_PACKET -> 1;
            default -> 2;
        };
    }

    /** 核销/展示顺序：券 → 红包 → 积分 → 活动（与 P-7 编排顺序一致）。 */
    private static int consumptionOrder(String source) {
        return switch (source) {
            case SRC_COUPON -> 0;
            case SRC_RED_PACKET -> 1;
            case SRC_POINTS -> 2;
            default -> 3;
        };
    }

    private static boolean better(long score, long raw, int count, int mask,
                                  long bestScore, long bestRaw, int bestCount, int bestMask) {
        if (score != bestScore) {
            return score > bestScore;
        }
        if (raw != bestRaw) {
            return raw > bestRaw;
        }
        if (count != bestCount) {
            return count < bestCount;
        }
        return mask < bestMask;
    }

    private static boolean inFilter(List<String> filter, String id) {
        return filter == null || filter.isEmpty() || filter.contains(id);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static PromotionRejectDTO reject(String source, String refId, long potential, String code, String reason) {
        PromotionRejectDTO dto = new PromotionRejectDTO();
        dto.setSource(source);
        dto.setRefId(refId);
        dto.setPotentialAmount(potential);
        dto.setCode(code);
        dto.setReason(reason);
        return dto;
    }
}