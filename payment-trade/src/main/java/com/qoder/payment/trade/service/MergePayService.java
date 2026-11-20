package com.qoder.payment.trade.service;

import com.qoder.payment.common.db.ShardRowMirror;
import com.qoder.payment.common.db.ShardRuntime;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.payment.facade.trade.MergeCreateRequest;
import com.qoder.payment.facade.trade.MergeOrderDTO;
import com.qoder.payment.facade.trade.MergeRelDTO;
import com.qoder.payment.facade.trade.TradeTccContext;
import com.qoder.payment.trade.convert.OrderViews;
import com.qoder.payment.trade.dataobject.MergeOrderDO;
import com.qoder.payment.trade.dataobject.MergeRelDO;
import com.qoder.payment.trade.dataobject.OrderDO;
import com.qoder.payment.trade.mapper.MergeOrderMapper;
import com.qoder.payment.trade.mapper.OrderMapper;
import com.qoder.tcc.IdempotentTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * T-5 合并单（trade 侧）：多订单 → 一笔合并支付。
 *
 * <p><b>守恒</b>：total = Σ子订单额（long 分，误差 0）；入合并占位与受理占位全部 CAS，
 * 子订单金额快照在受理时复核（改价竞态 0 行拒绝）。
 *
 * <p><b>部分失败语义（整体回滚）</b>：合并支付是单全局事务多 trade 分支
 * （承载单 PAY + 各子订单 MERGE_PAY），任一子订单 Try 失败 → TCC 整体回滚：
 * 各子订单释放回 CREATED、承载单释放、合并单 PAYING→CREATED（paid_count=0 CAS），
 * 可用新 requestId 重付或拆单个别支付（合并单重开后子订单仍绑定本合并，须先重付或撤销释放）。
 *
 * <p><b>与 COMBO 边界</b>：合并=多订单一支付（trade 维度拆分），COMBO=一订单多支付腿
 * （资金维度拆分）；叠加时 Σ腿=合并额=Σ子订单额，两层守恒各自强校验（见 payment 域 MergePayOrchestrator）。
 */
@Slf4j
@Service
public class MergePayService {

    private final OrderMapper orderMapper;
    private final MergeOrderMapper mergeMapper;
    private final IdempotentTemplate idempotentTemplate;
    private final OrderViews views;
    private final ObjectProvider<ShardRuntime> shardRuntime;
    private final ObjectProvider<ShardRowMirror> shardRowMirror;
    private final TransactionTemplate tx;

    public MergePayService(OrderMapper orderMapper, MergeOrderMapper mergeMapper,
                           IdempotentTemplate idempotentTemplate, OrderViews views,
                           ObjectProvider<ShardRuntime> shardRuntime, ObjectProvider<ShardRowMirror> shardRowMirror,
                           PlatformTransactionManager txManager) {
        this.orderMapper = orderMapper;
        this.mergeMapper = mergeMapper;
        this.idempotentTemplate = idempotentTemplate;
        this.views = views;
        this.shardRuntime = shardRuntime;
        this.shardRowMirror = shardRowMirror;
        this.tx = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------ 创建

    public MergeOrderDTO createMergeOrder(MergeCreateRequest request) {
        if (request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "requestId 必填（幂等键）");
        }
        List<String> ids = request.getOrderIds() == null ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(request.getOrderIds()));
        if (ids.size() < 2) {
            throw new BizException(ResultCode.PARAM_ERROR, "合并支付至少两笔订单（去重后）");
        }
        return idempotentTemplate.execute("MERGE_CREATE", request.getRequestId(), MergeOrderDTO.class, () -> {
            MergeOrderDO existing = mergeMapper.selectByRequestId(request.getRequestId());
            if (existing != null) {
                return view(existing);
            }
            List<OrderDO> orders = new ArrayList<>();
            String merchantId = null;
            long total = 0;
            for (String id : ids) {
                OrderDO order = requireOrder(id);
                if (merchantId == null) {
                    merchantId = order.getMerchantId();
                }
                if (!merchantId.equals(order.getMerchantId())) {
                    throw new BizException(ResultCode.PARAM_ERROR, "合并支付要求同一商户的订单: " + id);
                }
                requireMergeable(order);
                total += order.getAmount();
                orders.add(order);
            }
            MoneyUtil.requireValid(total);
            MergeOrderDO merge = new MergeOrderDO();
            merge.setId("MG" + mergeMapper.nextId());
            merge.setMerchantId(merchantId);
            merge.setTotalAmount(total);
            merge.setStatus("CREATED");
            merge.setRequestId(request.getRequestId());
            merge.setOrderCount(orders.size());
            merge.setPaidCount(0);
            merge.setCreatedAt(System.currentTimeMillis());
            try {
                mergeMapper.insert(merge);
            } catch (DuplicateKeyException e) {
                MergeOrderDO again = mergeMapper.selectByRequestId(request.getRequestId());
                if (again == null) {
                    throw e;
                }
                return view(again);
            }
            for (OrderDO order : orders) {
                MergeRelDO rel = new MergeRelDO();
                rel.setMergeId(merge.getId());
                rel.setOrderId(order.getId());
                rel.setAmount(order.getAmount());
                rel.setFee(0L);
                mergeMapper.insertRel(rel);
                // CAS 占位：任一子订单已被预留/入合并/设阶段计划 → 整体拒绝（幂等模板同事务回滚）
                if (orderMapper.attachMerge(order.getId(), merge.getId()) != 1) {
                    throw new BizException(ResultCode.CONFLICT, "子订单状态已变更，入合并失败: " + order.getId());
                }
                mirror(order.getId());
            }
            log.info("合并单创建 mergeId={} merchantId={} total={} orders={}", merge.getId(), merchantId, total, ids);
            return view(merge);
        });
    }

    private static void requireMergeable(OrderDO order) {
        if (!"TRADE".equals(order.getBizType())) {
            throw new BizException(ResultCode.PARAM_ERROR, "仅商户交易订单可合并: " + order.getId());
        }
        if (order.getProductType() != null && !"INSTANT".equals(order.getProductType())) {
            throw new BizException(ResultCode.PARAM_ERROR, "仅即时到账产品可合并（担保/预授权走各自工作流）: " + order.getId());
        }
        if (!"CREATED".equals(order.getStatus()) || order.getPaymentId() != null) {
            throw new BizException(ResultCode.CONFLICT, "仅未支付 CREATED 订单可合并: " + order.getId() + " " + order.getStatus());
        }
        if (order.getMergeId() != null) {
            throw new BizException(ResultCode.CONFLICT, "订单已在合并单中: " + order.getId() + " merge=" + order.getMergeId());
        }
        if (order.getStageCount() != null && order.getStageCount() > 0) {
            throw new BizException(ResultCode.CONFLICT, "分阶段订单不可合并: " + order.getId());
        }
        if (order.getRefundedAmount() != null && order.getRefundedAmount() > 0) {
            throw new BizException(ResultCode.CONFLICT, "订单存在退款记录，不可合并: " + order.getId());
        }
    }

    // ------------------------------------------------------------------ 查询

    public MergeOrderDTO getMergeOrder(String mergeId) {
        return view(requireMerge(mergeId));
    }

    // ------------------------------------------------------------------ 受理占位（payment 域编排调用）

    public MergeOrderDTO prepareMergePay(String mergeId, String paymentId, String carrierOrderId, List<Long> relFees) {
        MergeOrderDO merge = requireMerge(mergeId);
        List<MergeRelDO> rels = mergeMapper.selectRels(mergeId);
        if (relFees == null || relFees.size() != rels.size()) {
            throw new BizException(ResultCode.PARAM_ERROR, "fee 分摊行数与子订单数不一致");
        }
        // 受理复核：子订单仍 CREATED 且金额与入合并快照一致（改价/关闭竞态守卫，误差 0）
        long sum = 0;
        for (MergeRelDO rel : rels) {
            OrderDO order = requireOrder(rel.getOrderId());
            if (!"CREATED".equals(order.getStatus()) || order.getPaymentId() != null
                    || !mergeId.equals(order.getMergeId())) {
                throw new BizException(ResultCode.CONFLICT, "子订单状态已变更，合并受理拒绝: " + rel.getOrderId());
            }
            if (order.getAmount() == null || rel.getAmount() == null
                    || order.getAmount().longValue() != rel.getAmount().longValue()) {
                throw new BizException(ResultCode.CONFLICT, "子订单金额已变更（改价竞态），合并受理拒绝: " + rel.getOrderId());
            }
            sum += rel.getAmount();
        }
        if (sum != merge.getTotalAmount()) {
            throw new BizException(ResultCode.BIZ_ERROR, "合并金额不守恒: Σ子订单=" + sum + " ≠ 合并额=" + merge.getTotalAmount());
        }
        tx.executeWithoutResult(s -> {
            int flipped = mergeMapper.markPaying(mergeId, paymentId, carrierOrderId);
            if (flipped != 1) {
                MergeOrderDO current = requireMerge(mergeId);
                if ("PAYING".equals(current.getStatus()) && paymentId.equals(current.getPaymentId())) {
                    return; // 幂等重入（同支付单）
                }
                throw new BizException(ResultCode.CONFLICT, "合并单状态不可受理: " + current.getStatus());
            }
            for (int i = 0; i < rels.size(); i++) {
                long fee = relFees.get(i) == null ? 0L : relFees.get(i);
                if (fee < 0) {
                    throw new BizException(ResultCode.PARAM_ERROR, "fee 分摊不可为负");
                }
                mergeMapper.updateRelFee(mergeId, rels.get(i).getOrderId(), fee);
            }
        });
        log.info("合并受理占位 mergeId={} paymentId={} carrier={} total={}", mergeId, paymentId, carrierOrderId, merge.getTotalAmount());
        return view(requireMerge(mergeId));
    }

    /** 整体回滚/支付单失败后重开（幂等）：子订单须已释放；承载单 CREATED 则关闭留痕。 */
    public MergeOrderDTO reopenMerge(String mergeId) {
        MergeOrderDO merge = requireMerge(mergeId);
        if ("CREATED".equals(merge.getStatus())) {
            return view(merge);
        }
        if (!"PAYING".equals(merge.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "仅 PAYING 合并单可重开: " + merge.getStatus());
        }
        for (MergeRelDO rel : mergeMapper.selectRels(mergeId)) {
            OrderDO order = requireOrder(rel.getOrderId());
            // CLOSED 是合法终态（到期关闭/撤销），资金从未预留或已随 TCC 回滚释放，不应阻塞重开；
            // 原判定要求全部子订单必须 CREATED，导致「到期关闭与 payMerge 并发」时合并单永久 PAYING。
            boolean released = "CLOSED".equals(order.getStatus())
                    || ("CREATED".equals(order.getStatus()) && order.getPaymentId() == null);
            if (!released) {
                throw new BizException(ResultCode.CONFLICT, "子订单未释放，不可重开（待 TCC 回滚收敛）: " + rel.getOrderId());
            }
        }
        if (merge.getCarrierOrderId() != null) {
            OrderDO carrier = readYourWrite(() -> orderMapper.selectById(merge.getCarrierOrderId()));
            if (carrier != null && "CREATED".equals(carrier.getStatus())) {
                orderMapper.close(carrier.getId(), System.currentTimeMillis());
                mirror(carrier.getId());
            }
        }
        if (mergeMapper.reopenOnRollback(mergeId, merge.getPaymentId()) != 1) {
            MergeOrderDO current = requireMerge(mergeId);
            if (!"CREATED".equals(current.getStatus())) {
                throw new BizException(ResultCode.CONFLICT, "合并单重开让位: " + current.getStatus());
            }
        }
        log.info("合并单重开 mergeId={}（整体回滚后可重付）", mergeId);
        return view(requireMerge(mergeId));
    }

    // ------------------------------------------------------------------ 撤销后释放（T-5/T-6）

    /** 当日撤销成功后释放（幂等，CAS 守卫）：子订单可重付、承载单关闭、合并单重开。 */
    public MergeOrderDTO releaseMergeAfterCancel(String mergeId, String paymentId) {
        MergeOrderDO merge = requireMerge(mergeId);
        if (!paymentId.equals(merge.getPaymentId())) {
            return view(merge); // 已释放并重付/拆分（历史撤销幂等空操作）
        }
        if ("CREATED".equals(merge.getStatus())) {
            return view(merge); // 已释放（幂等重入）
        }
        if (!"PAID".equals(merge.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "合并单状态/支付单不匹配，撤销释放拒绝: " + merge.getStatus());
        }
        long now = System.currentTimeMillis();
        for (MergeRelDO rel : mergeMapper.selectRels(mergeId)) {
            int flipped = orderMapper.releaseMergeSubAfterCancel(rel.getOrderId(), paymentId);
            mirror(rel.getOrderId());
            if (flipped != 1) {
                OrderDO order = requireOrder(rel.getOrderId());
                if (!"CREATED".equals(order.getStatus()) || order.getPaymentId() != null) {
                    throw new BizException(ResultCode.CONFLICT, "子订单不可释放（已退款/状态变更）: " + rel.getOrderId());
                }
            }
        }
        if (merge.getCarrierOrderId() != null) {
            int closed = orderMapper.closePaidAfterCancel(merge.getCarrierOrderId(), paymentId, now);
            mirror(merge.getCarrierOrderId());
            if (closed != 1) {
                OrderDO carrier = requireOrder(merge.getCarrierOrderId());
                if (!"CLOSED".equals(carrier.getStatus())) {
                    throw new BizException(ResultCode.CONFLICT, "承载单不可关闭: " + carrier.getStatus());
                }
            }
        }
        if (mergeMapper.releaseAfterCancel(mergeId, paymentId) != 1) {
            MergeOrderDO current = requireMerge(mergeId);
            if (!"CREATED".equals(current.getStatus())) {
                throw new BizException(ResultCode.CONFLICT, "合并单释放让位: " + current.getStatus());
            }
        }
        log.info("合并撤销释放 mergeId={} paymentId={}（子订单可重付）", mergeId, paymentId);
        return view(requireMerge(mergeId));
    }

    // ------------------------------------------------------------------ 拆分语义（T-5）

    /**
     * 合并单拆分（明确拆分语义）：仅 CREATED（从未支付或整体回滚/撤销释放后）可拆，
     * 子订单解绑 merge_id 后可个别支付/改价/入新合并；合并单 DISSOLVED 终态留痕。幂等。
     */
    public MergeOrderDTO dissolveMerge(String mergeId) {
        MergeOrderDO merge = requireMerge(mergeId);
        if ("DISSOLVED".equals(merge.getStatus())) {
            return view(merge);
        }
        if (!"CREATED".equals(merge.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "仅未支付/已释放的合并单可拆分: " + merge.getStatus());
        }
        for (MergeRelDO rel : mergeMapper.selectRels(mergeId)) {
            OrderDO order = requireOrder(rel.getOrderId());
            if (!"CREATED".equals(order.getStatus()) || order.getPaymentId() != null) {
                throw new BizException(ResultCode.CONFLICT, "子订单未释放，不可拆分: " + rel.getOrderId());
            }
        }
        if (mergeMapper.dissolve(mergeId) != 1) {
            MergeOrderDO current = requireMerge(mergeId);
            if (!"DISSOLVED".equals(current.getStatus())) {
                throw new BizException(ResultCode.CONFLICT, "合并单拆分让位: " + current.getStatus());
            }
        }
        for (MergeRelDO rel : mergeMapper.selectRels(mergeId)) {
            orderMapper.detachMerge(rel.getOrderId(), mergeId);
            mirror(rel.getOrderId());
        }
        if (merge.getCarrierOrderId() != null) {
            OrderDO carrier = readYourWrite(() -> orderMapper.selectById(merge.getCarrierOrderId()));
            if (carrier != null && "CREATED".equals(carrier.getStatus())) {
                orderMapper.close(carrier.getId(), System.currentTimeMillis());
                mirror(carrier.getId());
            }
        }
        log.info("合并单拆分 mergeId={}（子订单解绑可个别支付）", mergeId);
        return view(requireMerge(mergeId));
    }

    // ------------------------------------------------------------------ TCC 分支动作（MERGE_PAY）

    /** Try：子订单预留（金额快照 CAS 守卫）。 */
    public void tryMergeSub(TradeTccContext ctx) {
        OrderDO order = requireOrder(ctx.getOrderId());
        if (!ctx.getMergeId().equals(order.getMergeId())) {
            throw new BizException(ResultCode.CONFLICT, "子订单不属于本合并单: " + ctx.getOrderId());
        }
        int reserved = orderMapper.reserveMergeSub(ctx.getOrderId(), ctx.getPaymentId(), ctx.getMergeId(), ctx.getAmount());
        mirror(ctx.getOrderId());
        if (reserved != 1) {
            throw new BizException(ResultCode.CONFLICT, "子订单状态已变更（合并 Try 失败，整体回滚）: "
                    + ctx.getOrderId() + " " + order.getStatus());
        }
        log.info("合并子订单预留 orderId={} mergeId={} paymentId={}", ctx.getOrderId(), ctx.getMergeId(), ctx.getPaymentId());
    }

    /** Confirm：子订单 PAID + 合并计数（与分支日志同事务，只翻一次；全付则合并 PAID）。 */
    public void confirmMergeSub(TradeTccContext ctx) {
        long now = System.currentTimeMillis();
        int flipped = orderMapper.markPaidMergeSub(ctx.getOrderId(), ctx.getPaymentId(), now);
        mirror(ctx.getOrderId());
        if (flipped == 1) {
            mergeMapper.bumpPaid(ctx.getMergeId());
            mergeMapper.finishIfAllPaid(ctx.getMergeId(), now);
            log.info("合并子订单已付 orderId={} mergeId={}", ctx.getOrderId(), ctx.getMergeId());
        } else {
            OrderDO order = requireOrder(ctx.getOrderId());
            if (!"PAID".equals(order.getStatus()) || !ctx.getPaymentId().equals(order.getPaymentId())) {
                throw new BizException(ResultCode.CONFLICT, "合并子订单确认让位: " + ctx.getOrderId() + " " + order.getStatus());
            }
            // 幂等重入：确保合并终态收敛（崩溃窗口兜底）
            mergeMapper.finishIfAllPaid(ctx.getMergeId(), now);
        }
    }

    /** Cancel：子订单释放 + 合并单重开（空回滚 0 行无害；多分支重复 CAS 只一方生效）。 */
    public void cancelMergeSub(TradeTccContext ctx) {
        orderMapper.releaseMergeSub(ctx.getOrderId(), ctx.getPaymentId());
        mirror(ctx.getOrderId());
        mergeMapper.reopenOnRollback(ctx.getMergeId(), ctx.getPaymentId());
        log.info("合并子订单释放 orderId={} mergeId={} paymentId={}", ctx.getOrderId(), ctx.getMergeId(), ctx.getPaymentId());
    }

    // ------------------------------------------------------------------ 内部

    private MergeOrderDO requireMerge(String mergeId) {
        MergeOrderDO merge = mergeMapper.selectById(mergeId);
        if (merge == null) {
            throw new BizException(ResultCode.NOT_FOUND, "合并单不存在: " + mergeId);
        }
        return merge;
    }

    private OrderDO requireOrder(String orderId) {
        OrderDO order = readYourWrite(() -> orderMapper.selectById(orderId));
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND, "订单不存在: " + orderId);
        }
        return order;
    }

    private MergeOrderDTO view(MergeOrderDO merge) {
        MergeOrderDTO dto = new MergeOrderDTO();
        dto.setId(merge.getId());
        dto.setMerchantId(merge.getMerchantId());
        dto.setTotalAmount(merge.getTotalAmount());
        dto.setStatus(merge.getStatus());
        dto.setRequestId(merge.getRequestId());
        dto.setCarrierOrderId(merge.getCarrierOrderId());
        dto.setPaymentId(merge.getPaymentId());
        dto.setOrderCount(merge.getOrderCount());
        dto.setPaidCount(merge.getPaidCount());
        dto.setCreatedAt(merge.getCreatedAt());
        dto.setPaidAt(merge.getPaidAt());
        List<MergeRelDTO> rels = new ArrayList<>();
        for (MergeRelDO rel : mergeMapper.selectRels(merge.getId())) {
            MergeRelDTO r = new MergeRelDTO();
            r.setMergeId(rel.getMergeId());
            r.setOrderId(rel.getOrderId());
            r.setAmount(rel.getAmount());
            r.setFee(rel.getFee());
            OrderDO order = readYourWrite(() -> orderMapper.selectById(rel.getOrderId()));
            if (order != null) {
                r.setOrderStatus(order.getStatus());
                r.setRefundedAmount(order.getRefundedAmount());
            }
            rels.add(r);
        }
        dto.setRels(rels);
        return dto;
    }

    private void mirror(String orderId) {
        ShardRowMirror mirror = shardRowMirror.getIfAvailable();
        if (mirror != null) {
            mirror.mirror("trade_order", orderId);
        }
    }

    private <T> T readYourWrite(Supplier<T> action) {
        ShardRuntime runtime = shardRuntime.getIfAvailable();
        return runtime == null ? action.get() : runtime.readYourWrite(action);
    }
}