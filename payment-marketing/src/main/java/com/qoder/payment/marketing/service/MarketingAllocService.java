package com.qoder.payment.marketing.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.marketing.AllocItemDTO;
import com.qoder.payment.facade.marketing.AllocTargetDTO;
import com.qoder.payment.facade.marketing.MarketingAllocDTO;
import com.qoder.payment.facade.marketing.MarketingAllocRequest;
import com.qoder.payment.facade.marketing.MarketingConservationDTO;
import com.qoder.payment.marketing.mapper.MarketingAllocMapper;
import com.qoder.tcc.IdempotentTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * X-3b 营销抵扣分摊（订单 / 商品 / 商户维度）——<b>守恒：Σ分摊 = 抵扣总额，误差 0</b>。
 *
 * <p><b>算法</b>：WEIGHT 模式按权重占比先向下取整，再用<b>最大余数法</b>把余下的「分」逐一补给
 * 小数余数最大的目标（同余数按入参顺序），因此 Σ 恒等于总额，绝不因四舍五入产生 1 分漂移；
 * EXPLICIT 模式直接校验 Σamount == totalAmount，不等即拒绝（绝不落半成品分摊行）。
 * 全程 long「分」整数运算（MoneyUtil 口径），无浮点。
 *
 * <p><b>与 P-7 的边界（范围红线）</b>：本服务<b>只做分摊计算与守恒校验</b>，
 * <b>不编排支付腿、不动账、不写 payment/trade/settle 任何单据</b>。P-7 营销抵扣编排后续由 payment 域
 * 调用 {@code useCoupon/useRedPacket/usePoints/applyActivity} 完成核销，再调 {@link #allocate} 落目标维度分摊，
 * 并用 {@link #checkConservation} 做「订单额 = 资金腿 + 营销腿」强校验。
 *
 * <p><b>手续费口径不变</b>：分摊只针对营销抵扣额，gross/fee/net 与 feeBps→SYS_FEE 由 settle 域负责，
 * 本服务不读不写手续费，Σbalance=0 与试算平衡不受影响（分摊行本身不是会计分录）。
 *
 * <p><b>幂等</b>：{@code (MARKETING_ALLOC, paymentId:requestId)} + {@code marketing_alloc}
 * UNIQUE(payment_id,dimension,source,target_id,type) + UNIQUE(biz_no)。
 * 同一支付单只允许一份目标维度分摊（换 requestId 重复分摊直接拒绝），杜绝 Σ 翻倍破坏守恒。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketingAllocService {

    public static final String MODE_WEIGHT = "WEIGHT";
    public static final String MODE_EXPLICIT = "EXPLICIT";
    public static final String TARGET_ORDER = "ORDER";
    public static final String TARGET_ITEM = "ITEM";
    public static final String TARGET_MERCHANT = "MERCHANT";
    /** 金额上限（分），与 MoneyUtil 1e12 口径一致；权重上限防乘法溢出。 */
    private static final long MAX_AMOUNT = 1_000_000_000_000L;
    private static final long MAX_WEIGHT = 1_000_000L;

    private final MarketingAllocMapper allocMapper;
    private final MarketingSupport support;
    private final MarketingFundService fundService;
    private final IdempotentTemplate idempotentTemplate;

    public MarketingAllocDTO allocate(MarketingAllocRequest request) {
        MarketingSupport.require(request != null, "请求必填");
        MarketingSupport.require(MarketingSupport.notBlank(request.getPaymentId()), "paymentId 必填");
        MarketingSupport.require(request.getTargets() != null && !request.getTargets().isEmpty(), "targets 必填");
        String requestId = MarketingSupport.notBlank(request.getRequestId()) ? request.getRequestId() : "DEFAULT";
        String key = request.getPaymentId() + ":" + requestId;
        MarketingSupport.requireKey(key);
        String mode = MarketingSupport.notBlank(request.getMode()) ? request.getMode() : MODE_WEIGHT;
        MarketingSupport.require(MODE_WEIGHT.equals(mode) || MODE_EXPLICIT.equals(mode),
                "mode 必须为 WEIGHT 或 EXPLICIT");

        List<AllocTargetDTO> targets = request.getTargets();
        for (AllocTargetDTO target : targets) {
            MarketingSupport.require(target != null, "target 不得为空");
            MarketingSupport.require(TARGET_ORDER.equals(target.getTargetType())
                            || TARGET_ITEM.equals(target.getTargetType())
                            || TARGET_MERCHANT.equals(target.getTargetType()),
                    "targetType 必须为 ORDER/ITEM/MERCHANT");
            MarketingSupport.require(MarketingSupport.notBlank(target.getTargetId()), "targetId 必填");
        }
        long sourceNet = fundService.sourceNet(request.getPaymentId());
        boolean hasSource = !allocMapper.selectByDimension(request.getPaymentId(),
                MarketingFundService.DIM_SOURCE).isEmpty();
        long total = request.getTotalAmount() != null ? request.getTotalAmount() : sourceNet;
        MarketingSupport.require(total >= 0 && total <= MAX_AMOUNT, "totalAmount 需在 [0,1e12] 分内");
        if (hasSource) {
            // 已有核销 ⇒ 分摊总额必须等于营销核销净额（守恒前置校验，不等即拒绝，绝不动账/落行）
            MarketingSupport.require(total == sourceNet,
                    "分摊总额与营销核销净额不一致：total=" + total + " sourceNet=" + sourceNet);
        }
        long[] amounts = MODE_EXPLICIT.equals(mode)
                ? explicitAmounts(targets, total) : weightAmounts(targets, total);

        return idempotentTemplate.execute("MARKETING_ALLOC", key, MarketingAllocDTO.class, () -> {
            List<AllocItemDTO> existingTargets = allocMapper.selectByDimension(request.getPaymentId(),
                    MarketingFundService.DIM_TARGET);
            String firstBizNo = targetBizNo(key, targets.get(0));
            if (!existingTargets.isEmpty()) {
                boolean sameRequest = existingTargets.stream()
                        .allMatch(row -> row.getBizNo() != null && row.getBizNo().startsWith("AL:T:" + key + ":"));
                if (!sameRequest) {
                    throw new BizException(ResultCode.CONFLICT,
                            "该支付单已存在其它 requestId 的分摊，重复分摊会破坏守恒: " + request.getPaymentId());
                }
                if (allocMapper.selectByBiz(firstBizNo) != null) {
                    return getAllocation(request.getPaymentId());
                }
            }
            long now = System.currentTimeMillis();
            long sum = 0L;
            for (int i = 0; i < targets.size(); i++) {
                AllocTargetDTO target = targets.get(i);
                AllocItemDTO item = new AllocItemDTO();
                item.setId(support.newId("AL"));
                item.setPaymentId(request.getPaymentId());
                item.setDimension(MarketingFundService.DIM_TARGET);
                item.setSource(target.getTargetType());
                item.setTargetId(target.getTargetId());
                item.setAmount(amounts[i]);
                item.setType(MarketingFundService.TYPE_USE);
                item.setBizNo(targetBizNo(key, target));
                item.setCreatedAt(now);
                allocMapper.insert(item);
                sum += amounts[i];
            }
            // 落行后再校验一次守恒（双保险）：不等即抛异常整体回滚，绝不留下不守恒的分摊
            if (sum != total) {
                throw new BizException(ResultCode.BIZ_ERROR, "分摊不守恒：Σ=" + sum + " total=" + total);
            }
            support.audit("ALLOC", request.getPaymentId(), "ALLOCATE", null, null, total, key,
                    request.getOperator(), "营销抵扣分摊 mode=" + mode + " targets=" + targets.size()
                            + " Σ=" + sum + " diff=0");
            return getAllocation(request.getPaymentId());
        });
    }

    private static String targetBizNo(String key, AllocTargetDTO target) {
        String bizNo = "AL:T:" + key + ":" + target.getTargetType() + ":" + target.getTargetId();
        MarketingSupport.require(bizNo.length() <= 128, "分摊幂等键过长（≤128）");
        return bizNo;
    }

    private static long[] explicitAmounts(List<AllocTargetDTO> targets, long total) {
        long[] amounts = new long[targets.size()];
        long sum = 0L;
        for (int i = 0; i < targets.size(); i++) {
            Long amount = targets.get(i).getAmount();
            MarketingSupport.require(amount != null && amount >= 0, "EXPLICIT 模式每个 target 需 amount ≥ 0");
            amounts[i] = amount;
            sum += amount;
        }
        if (sum != total) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "EXPLICIT 分摊不守恒：Σamount=" + sum + " ≠ totalAmount=" + total + "（误差必须为 0）");
        }
        return amounts;
    }

    /** 最大余数法：先按权重向下取整，再把余数逐一补给小数余数最大者 ⇒ Σ 恒等于 total。 */
    private static long[] weightAmounts(List<AllocTargetDTO> targets, long total) {
        int n = targets.size();
        long[] weights = new long[n];
        long sumWeight = 0L;
        for (int i = 0; i < n; i++) {
            Long weight = targets.get(i).getWeight();
            MarketingSupport.require(weight != null && weight > 0 && weight <= MAX_WEIGHT,
                    "WEIGHT 模式每个 target 需 weight ∈ (0,1e6]");
            weights[i] = weight;
            sumWeight += weight;
        }
        long[] amounts = new long[n];
        long[] remainder = new long[n];
        long assigned = 0L;
        for (int i = 0; i < n; i++) {
            long numerator = total * weights[i];
            amounts[i] = numerator / sumWeight;
            remainder[i] = numerator % sumWeight;
            assigned += amounts[i];
        }
        long left = total - assigned;
        List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingLong((Integer i) -> remainder[i]).reversed()
                .thenComparingInt(i -> i));
        for (int k = 0; k < left; k++) {
            amounts[order.get(k)] += 1L;
        }
        return amounts;
    }

    /**
     * P-7 <b>退回后目标维度重分摊</b>（X-3b 遗留项，幂等）：
     * 按当前来源净额 {@code S = Σ核销 − Σ退回} 重算目标分布，使 {@code Σ目标净额 = S，diff = 0}。
     *
     * <p><b>算法</b>：以各目标原 USE 额为权重，用<b>最大余数法</b>（BigInteger 精确乘除，杜绝溢出）
     * 把 S 拆到各目标得 {@code d_i}；再令调整行 {@code r_i = u_i − d_i}（REFUND），
     * 于是目标净额 {@code u_i − r_i = d_i}，Σ 恒等于 S，误差 0。
     *
     * <p><b>只追加/收敛边界</b>：SOURCE 维度（与资金凭证 1:1）与 TARGET 维度 USE 行<b>绝不改写</b>；
     * 只创建/收敛由本方法自己产生的 TARGET REFUND 调整行（{@code biz_no=AL:TR:…}，
     * UNIQUE(payment_id,dimension,source,target_id,type) 天然每目标一行）。每次重分摊落审计留痕。
     *
     * <p><b>幂等</b>：{@code (MARKETING_REALLOC, paymentId:S)} —— 同一净额重放返回既有结果，不产生新行。
     * 无目标行且 S&gt;0 时按缺省单 ORDER 目标（targetId=paymentId）建分摊；
     * S 超过既有目标 USE 总额时拒绝（需先 allocate 新目标），绝不落不守恒分摊。
     */
    public MarketingAllocDTO reallocate(String paymentId, String requestId) {
        MarketingSupport.require(MarketingSupport.notBlank(paymentId), "paymentId 必填");
        long sourceNet = fundService.sourceNet(paymentId);
        MarketingSupport.require(sourceNet >= 0, "来源净额为负，数据异常: " + paymentId);
        String key = paymentId + ":" + sourceNet;
        MarketingSupport.requireKey(key);
        String trace = MarketingSupport.notBlank(requestId) ? requestId : "DEFAULT";
        return idempotentTemplate.execute("MARKETING_REALLOC", key, MarketingAllocDTO.class, () -> {
            List<AllocItemDTO> targets = allocMapper.selectByDimension(paymentId, MarketingFundService.DIM_TARGET)
                    .stream().filter(row -> MarketingFundService.TYPE_USE.equals(row.getType())).toList();
            if (targets.isEmpty()) {
                if (sourceNet == 0L) {
                    return getAllocation(paymentId);
                }
                // 从未分摊过：按缺省单 ORDER 目标建分摊（复用 allocate 的守恒校验与幂等）
                AllocTargetDTO order = new AllocTargetDTO();
                order.setTargetType(TARGET_ORDER);
                order.setTargetId(paymentId);
                order.setWeight(1L);
                MarketingAllocRequest request = new MarketingAllocRequest();
                request.setPaymentId(paymentId);
                request.setRequestId(trace);
                request.setMode(MODE_WEIGHT);
                request.setTargets(List.of(order));
                request.setOperator("P-7-REALLOC");
                allocate(request);
                return getAllocation(paymentId);
            }
            long useSum = targets.stream().mapToLong(row -> MarketingSupport.nz(row.getAmount())).sum();
            MarketingSupport.requireBiz(sourceNet <= useSum,
                    "来源净额 " + sourceNet + " 超过既有目标分摊总额 " + useSum + "，请先 allocate 新目标（绝不落不守恒分摊）");
            long[] desired = remainderSplit(sourceNet, targets);
            long now = System.currentTimeMillis();
            long adjustSum = 0L;
            for (int i = 0; i < targets.size(); i++) {
                AllocItemDTO target = targets.get(i);
                long use = MarketingSupport.nz(target.getAmount());
                long adjust = use - desired[i];
                MarketingSupport.require(adjust >= 0, "目标调整额为负，数据异常: " + target.getTargetId());
                adjustSum += adjust;
                String bizNo = adjustBizNo(paymentId, target);
                AllocItemDTO existing = allocMapper.selectByBiz(bizNo);
                if (existing == null) {
                    if (adjust == 0L) {
                        continue;
                    }
                    AllocItemDTO row = new AllocItemDTO();
                    row.setId(support.newId("AL"));
                    row.setPaymentId(paymentId);
                    row.setDimension(MarketingFundService.DIM_TARGET);
                    row.setSource(target.getSource());
                    row.setTargetId(target.getTargetId());
                    row.setAmount(adjust);
                    row.setType(MarketingFundService.TYPE_REFUND);
                    row.setBizNo(bizNo);
                    row.setCreatedAt(now);
                    allocMapper.insert(row);
                } else if (MarketingSupport.nz(existing.getAmount()) != adjust) {
                    allocMapper.updateTargetAdjust(bizNo, adjust);
                }
            }
            long targetNet = fundService.targetNet(paymentId);
            MarketingSupport.requireBiz(targetNet == sourceNet,
                    "重分摊后不守恒：Σ目标=" + targetNet + " ≠ Σ来源=" + sourceNet);
            support.audit("ALLOC", paymentId, "REALLOC", null, null, sourceNet, key, "P-7",
                    "退回后目标维度重分摊：Σ来源=" + sourceNet + " Σ目标=" + targetNet + " 调整额合计=" + adjustSum
                            + " diff=0 trace=" + trace);
            return getAllocation(paymentId);
        });
    }

    private static String adjustBizNo(String paymentId, AllocItemDTO target) {
        String bizNo = "AL:TR:" + paymentId + ":" + target.getSource() + ":" + target.getTargetId();
        MarketingSupport.require(bizNo.length() <= 128, "重分摊幂等键过长（≤128）");
        return bizNo;
    }

    /** 最大余数法（BigInteger 精确，权重=原目标 USE 额，无 1e6 权重上限约束）：Σ 恒等于 total。 */
    private static long[] remainderSplit(long total, List<AllocItemDTO> targets) {
        int n = targets.size();
        java.math.BigInteger[] weights = new java.math.BigInteger[n];
        java.math.BigInteger sumWeight = java.math.BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            weights[i] = java.math.BigInteger.valueOf(Math.max(0L, MarketingSupport.nz(targets.get(i).getAmount())));
            sumWeight = sumWeight.add(weights[i]);
        }
        long[] amounts = new long[n];
        if (sumWeight.signum() == 0) {
            // 原目标额全 0（极端退化）：整额落首个目标，仍守恒
            amounts[0] = total;
            return amounts;
        }
        java.math.BigInteger totalBig = java.math.BigInteger.valueOf(total);
        java.math.BigInteger[] remainder = new java.math.BigInteger[n];
        long assigned = 0L;
        for (int i = 0; i < n; i++) {
            java.math.BigInteger numerator = totalBig.multiply(weights[i]);
            java.math.BigInteger[] qr = numerator.divideAndRemainder(sumWeight);
            amounts[i] = qr[0].longValueExact();
            remainder[i] = qr[1];
            assigned += amounts[i];
        }
        long left = total - assigned;
        List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort(Comparator.comparing((Integer i) -> remainder[i]).reversed().thenComparingInt(i -> i));
        for (int k = 0; k < left; k++) {
            amounts[order.get(k)] += 1L;
        }
        return amounts;
    }

    public MarketingAllocDTO getAllocation(String paymentId) {
        MarketingSupport.require(MarketingSupport.notBlank(paymentId), "paymentId 必填");
        List<AllocItemDTO> sources = allocMapper.selectByDimension(paymentId, MarketingFundService.DIM_SOURCE);
        List<AllocItemDTO> targets = allocMapper.selectByDimension(paymentId, MarketingFundService.DIM_TARGET);
        long sourceSum = fundService.sourceNet(paymentId);
        long targetSum = fundService.targetNet(paymentId);
        long total = sources.isEmpty() ? targetSum : sourceSum;
        MarketingAllocDTO dto = new MarketingAllocDTO();
        dto.setPaymentId(paymentId);
        dto.setTotalAmount(total);
        dto.setAllocated(targetSum);
        dto.setSourceSum(sourceSum);
        dto.setDiff(targetSum - total);
        dto.setSourceDiff(sourceSum - total);
        dto.setBalanced(targetSum == total && sourceSum == total);
        dto.setSources(sources);
        dto.setTargets(targets);
        dto.setCreatedAt(System.currentTimeMillis());
        return dto;
    }

    /**
     * 守恒校验（只读，供 P-7 编排受理/完结时强校验）：
     * {@code orderAmount = fundAmount + marketingAmount}，且 {@code marketingAmount = Σ来源 = Σ目标}。
     * orderAmount/fundAmount 任一为空则跳过订单项校验（只校验营销内部两维度守恒）。
     */
    public MarketingConservationDTO checkConservation(String paymentId, Long orderAmount, Long fundAmount) {
        MarketingSupport.require(MarketingSupport.notBlank(paymentId), "paymentId 必填");
        long marketing = fundService.sourceNet(paymentId);
        long targetSum = fundService.targetNet(paymentId);
        MarketingConservationDTO dto = new MarketingConservationDTO();
        dto.setPaymentId(paymentId);
        dto.setOrderAmount(orderAmount);
        dto.setFundAmount(fundAmount);
        dto.setMarketingAmount(marketing);
        dto.setSourceSum(marketing);
        dto.setTargetSum(targetSum);
        dto.setSourceDiff(0L);
        dto.setTargetDiff(targetSum - marketing);
        Long orderDiff = null;
        if (orderAmount != null && fundAmount != null) {
            orderDiff = orderAmount - fundAmount - marketing;
        }
        dto.setOrderDiff(orderDiff);
        boolean balanced = dto.getTargetDiff() == 0L && (orderDiff == null || orderDiff == 0L);
        dto.setBalanced(balanced);
        dto.setMessage(balanced
                ? "守恒成立：营销抵扣 " + marketing + " = Σ来源 = Σ目标"
                + (orderDiff == null ? "" : "，订单额 = 资金腿 + 营销腿")
                : "守恒不成立：targetDiff=" + dto.getTargetDiff() + " orderDiff=" + orderDiff);
        return dto;
    }
}