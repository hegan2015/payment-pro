package com.qoder.payment.trade.service;

import com.qoder.payment.common.db.ShardRowMirror;
import com.qoder.payment.common.db.ShardRuntime;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.TradeTccContext;
import com.qoder.payment.trade.convert.OrderViews;
import com.qoder.payment.trade.dataobject.OrderDO;
import com.qoder.payment.trade.dataobject.OrderStagePlanDO;
import com.qoder.payment.trade.dataobject.StagePayLogDO;
import com.qoder.payment.trade.mapper.OrderMapper;
import com.qoder.payment.trade.mapper.StagePayMapper;
import com.qoder.tcc.IdempotentTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;

/**
 * T-5 分阶段付款（trade 侧）：定金+尾款或按期 N 阶段。
 *
 * <p><b>守恒</b>：计划创建时 Σ期额 = 订单额（误差 0，强校验）；每期支付额必须等于计划额；
 * paid_amount 逐期累加，全款时 paid_stage = stage_count 且 paid_amount = amount。
 *
 * <p><b>状态机</b>：期状态 PROCESSING→SUCCESS/FAILED/CANCELLED（stage_pay_log，
 * UNIQUE(order_id,stage_seq) + UNIQUE(payment_id) + UNIQUE(request_id) 三重幂等）；
 * 订单侧顺序期 CAS（paid_stage=seq-1 守卫），期间订单恒 PAID（指向最新已付期支付单，
 * recon「SUCCESS 支付单 ⇔ 订单 PAID」口径不变），首期前 CREATED。
 *
 * <p><b>撤销</b>：仅最新已付期可当日撤销（payment 域 CAS 胜出后调 {@link #releaseStageAfterCancel}），
 * paid_stage/paid_amount 回退一期，订单回到 CREATED（首期）/PAID（指向上期）。
 */
@Slf4j
@Service
public class StagePayService {

    private final OrderMapper orderMapper;
    private final StagePayMapper stageMapper;
    private final IdempotentTemplate idempotentTemplate;
    private final OrderViews views;
    private final ObjectProvider<ShardRuntime> shardRuntime;
    private final ObjectProvider<ShardRowMirror> shardRowMirror;

    public StagePayService(OrderMapper orderMapper, StagePayMapper stageMapper,
                           IdempotentTemplate idempotentTemplate, OrderViews views,
                           ObjectProvider<ShardRuntime> shardRuntime, ObjectProvider<ShardRowMirror> shardRowMirror) {
        this.orderMapper = orderMapper;
        this.stageMapper = stageMapper;
        this.idempotentTemplate = idempotentTemplate;
        this.views = views;
        this.shardRuntime = shardRuntime;
        this.shardRowMirror = shardRowMirror;
    }

    // ------------------------------------------------------------------ 计划创建

    public OrderDTO createStagePlan(String orderId, List<Long> amounts, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "requestId 必填（幂等键）");
        }
        if (orderId == null || (orderId.length() + 1 + requestId.length()) > 64) {
            throw new BizException(ResultCode.PARAM_ERROR, "orderId:requestId 复合幂等键超长（≤64）");
        }
        if (amounts == null || amounts.size() < 2) {
            throw new BizException(ResultCode.PARAM_ERROR, "分阶段至少两期（定金+尾款）");
        }
        return idempotentTemplate.execute("STAGE_PLAN", orderId + ":" + requestId, OrderDTO.class, () -> {
            OrderDO order = require(orderId);
            List<OrderStagePlanDO> existing = stageMapper.selectPlan(orderId);
            if (!existing.isEmpty()) {
                // 幂等重入：同 requestId 返回既有计划；不同 requestId 拒绝重复设计划
                if (existing.size() == amounts.size()) {
                    boolean same = true;
                    for (int i = 0; i < amounts.size(); i++) {
                        if (existing.get(i).getAmount() != amounts.get(i).longValue()) {
                            same = false;
                            break;
                        }
                    }
                    if (same) {
                        return views.view(require(orderId));
                    }
                }
                throw new BizException(ResultCode.CONFLICT, "订单已有分阶段计划: " + orderId);
            }
            if (!"CREATED".equals(order.getStatus()) || order.getPaymentId() != null) {
                throw new BizException(ResultCode.CONFLICT, "仅未支付 CREATED 订单可设分阶段计划: " + orderId);
            }
            if (!"TRADE".equals(order.getBizType())
                    || (order.getProductType() != null && !"INSTANT".equals(order.getProductType()))) {
                throw new BizException(ResultCode.PARAM_ERROR, "仅即时到账商户交易可分阶段: " + orderId);
            }
            if (order.getMergeId() != null) {
                throw new BizException(ResultCode.CONFLICT, "合并单内订单不可分阶段: " + orderId);
            }
            long sum = 0;
            for (Long amount : amounts) {
                if (amount == null) {
                    throw new BizException(ResultCode.PARAM_ERROR, "期额必填");
                }
                MoneyUtil.requireValid(amount);
                sum += amount;
            }
            if (sum != order.getAmount()) {
                throw new BizException(ResultCode.PARAM_ERROR,
                        "分阶段金额不守恒: Σ期额=" + sum + " ≠ 订单额=" + order.getAmount());
            }
            if (orderMapper.setStagePlan(orderId, amounts.size()) != 1) {
                throw new BizException(ResultCode.CONFLICT, "订单状态已变更，设阶段计划失败: " + orderId);
            }
            mirror(orderId);
            for (int i = 0; i < amounts.size(); i++) {
                OrderStagePlanDO plan = new OrderStagePlanDO();
                plan.setOrderId(orderId);
                plan.setStageSeq(i + 1);
                plan.setAmount(amounts.get(i));
                stageMapper.insertPlan(plan);
            }
            log.info("分阶段计划 orderId={} stages={} total={}", orderId, amounts.size(), sum);
            return views.view(require(orderId));
        });
    }

    // ------------------------------------------------------------------ TCC 分支动作（STAGE_PAY）

    /** Try：顺序期预留 + 期留痕占位（同事务；UNIQUE(order_id,stage_seq) 防并发双付）。 */
    public void tryStage(TradeTccContext ctx) {
        int seq = ctx.getStageSeq();
        OrderDO order = require(ctx.getOrderId());
        if (order.getStageCount() == null || seq < 1 || seq > order.getStageCount()) {
            throw new BizException(ResultCode.PARAM_ERROR, "期序非法: " + seq);
        }
        int reserved = orderMapper.reserveStage(ctx.getOrderId(), ctx.getPaymentId(), seq - 1);
        mirror(ctx.getOrderId());
        if (reserved != 1) {
            throw new BizException(ResultCode.CONFLICT, "期序不可付（须顺序支付且无退款/合并）: "
                    + ctx.getOrderId() + " stage=" + seq + " paidStage=" + order.getPaidStage());
        }
        StagePayLogDO logRow = new StagePayLogDO();
        logRow.setId("SP" + stageMapper.nextId());
        logRow.setOrderId(ctx.getOrderId());
        logRow.setStageSeq(seq);
        logRow.setAmount(ctx.getAmount());
        logRow.setPaymentId(ctx.getPaymentId());
        logRow.setRequestId(ctx.getBizNo());
        logRow.setStatus("PROCESSING");
        logRow.setCreatedAt(System.currentTimeMillis());
        logRow.setUpdatedAt(logRow.getCreatedAt());
        stageMapper.insertLog(logRow);
        log.info("阶段预留 orderId={} stage={} paymentId={}", ctx.getOrderId(), seq, ctx.getPaymentId());
    }

    /** Confirm：paid_stage 推进 + paid_amount 累加 + 订单 PAID（CAS 只翻一次）+ 期留痕 SUCCESS。 */
    public void confirmStage(TradeTccContext ctx) {
        int seq = ctx.getStageSeq();
        long now = System.currentTimeMillis();
        int flipped = orderMapper.markStagePaid(ctx.getOrderId(), ctx.getPaymentId(), seq, seq - 1, ctx.getAmount(), now);
        mirror(ctx.getOrderId());
        if (flipped != 1) {
            OrderDO order = require(ctx.getOrderId());
            if (order.getPaidStage() == null || order.getPaidStage() < seq) {
                throw new BizException(ResultCode.CONFLICT, "阶段确认让位: " + ctx.getOrderId() + " stage=" + seq);
            }
        }
        stageMapper.finishLog(ctx.getPaymentId(), "SUCCESS", now);
        log.info("阶段已付 orderId={} stage={} amount={}", ctx.getOrderId(), seq, ctx.getAmount());
    }

    /** Cancel：释放本期预留（空回滚 0 行无害）+ 期留痕 CANCELLED。 */
    public void cancelStage(TradeTccContext ctx) {
        orderMapper.releaseStage(ctx.getOrderId(), ctx.getPaymentId());
        mirror(ctx.getOrderId());
        stageMapper.finishLog(ctx.getPaymentId(), "CANCELLED", System.currentTimeMillis());
        log.info("阶段释放 orderId={} stage={} paymentId={}", ctx.getOrderId(), ctx.getStageSeq(), ctx.getPaymentId());
    }

    // ------------------------------------------------------------------ 撤销后回退（T-5/T-6）

    /** 当日撤销成功后回退一期（幂等，CAS 守卫：仅最新已付期、未退、指向本支付单）。 */
    public OrderDTO releaseStageAfterCancel(String orderId, String paymentId) {
        StagePayLogDO logRow = stageMapper.selectLogByPayment(paymentId);
        if (logRow == null || !orderId.equals(logRow.getOrderId())) {
            throw new BizException(ResultCode.NOT_FOUND, "阶段支付留痕不存在: " + paymentId);
        }
        OrderDO order = require(orderId);
        int seq = logRow.getStageSeq();
        if ("CANCELLED".equals(logRow.getStatus())) {
            return views.view(order); // 已回退（幂等重入；后续期重付不影响本留痕终态）
        }
        int flipped = orderMapper.releaseStageAfterCancel(orderId, paymentId, seq, seq - 1, logRow.getAmount());
        mirror(orderId);
        if (flipped != 1) {
            OrderDO current = require(orderId);
            if (current.getPaidStage() == null || current.getPaidStage() != seq - 1) {
                throw new BizException(ResultCode.CONFLICT, "阶段撤销回退让位（非最新期/已退款/状态变更）: "
                        + orderId + " stage=" + seq + " paidStage=" + current.getPaidStage());
            }
        }
        stageMapper.cancelConfirmedLog(paymentId, System.currentTimeMillis());
        log.info("阶段撤销回退 orderId={} stage={} paymentId={}", orderId, seq, paymentId);
        return views.view(require(orderId));
    }

    // ------------------------------------------------------------------ 内部

    private OrderDO require(String orderId) {
        OrderDO order = readYourWrite(() -> orderMapper.selectById(orderId));
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND, "订单不存在: " + orderId);
        }
        return order;
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