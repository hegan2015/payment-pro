package com.qoder.payment.trade.service;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.db.ShardKeys;
import com.qoder.payment.common.db.ShardRowMirror;
import com.qoder.payment.common.db.ShardRuntime;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.facade.payment.PaymentFacade;
import com.qoder.payment.trade.dataobject.OrderDO;
import com.qoder.payment.trade.dataobject.OrderExpireDO;
import com.qoder.payment.trade.mapper.OrderExpireMapper;
import com.qoder.payment.trade.mapper.OrderMapper;
import com.qoder.payment.trade.product.ProductRouter;
import com.qoder.payment.trade.product.ProductStateMachine;
import com.qoder.payment.trade.product.TradeProductKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * T-4 未支付订单超时自动关闭（策略经 I-2 DynamicConfig 可配，触发经 I-3 JobHandler + ShedLock）。
 *
 * <p><b>防悬挂三重守卫</b>（已受理在途支付单绝不被误关）：
 * <ol>
 *   <li><b>前置快照</b>：扫描时一次性取支付单，凡该订单存在 PROCESSING/SUCCESS 支付单即跳过
 *       —— 覆盖「支付单已落库受理、TCC Try 尚未预留订单」的窗口；</li>
 *   <li><b>CAS 关闭</b>：{@code WHERE status='CREATED' AND payment_id IS NULL}，与支付受理
 *       {@code reserve} 的 {@code WHERE status='CREATED'} 互为排他：谁先谁赢，绝不双写；
 *       PAYING（已预留）/PAID/CLOSED 一律 0 行不动；</li>
 *   <li><b>后置复查 + 竞态回滚</b>：关闭后再取一次支付单快照，若本批刚关的订单出现在途支付单
 *       （关闭与受理同一瞬间竞态）→ 以 {@code CLOSED + 本次 closed_at + payment_id IS NULL} 三条件
 *       CAS 重开为 CREATED，让支付正常收敛，并留痕 reopened_at（绝不改他单他态）。</li>
 * </ol>
 *
 * <p><b>不重不漏</b>：候选按 orderId CRC32 取模分片（与 DB 分片同一算法族），
 * 逐片 ShedLock 互斥；关闭留痕 {@code order_expire_log(order_id UNIQUE)} 一单一次。
 * 存量订单 {@code expire_at IS NULL} ⇒ 永不自动关闭（数据保护）。
 *
 * <p><b>不自动跑批</b>：本地 @Scheduled 闸门默认 false，只由 XXL-Job / 控制台 / {@code /actuator/ppjob} 触发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExpireService {

    private final OrderMapper orderMapper;
    private final OrderExpireMapper expireMapper;
    private final ProductRouter productRouter;
    private final DynamicConfig dynamicConfig;
    private final ObjectProvider<ShardRuntime> shardRuntime;
    private final ObjectProvider<ShardRowMirror> shardRowMirror;

    @DubboReference
    private PaymentFacade paymentFacade;

    /** 扫描并关闭到期未支付订单，返回净关闭笔数（竞态回滚的不计入）。 */
    public int closeExpired(int shardIndex, int shardTotal) {
        int total = Math.max(1, shardTotal);
        int index = Math.max(0, Math.min(shardIndex, total - 1));
        int batch = Math.max(1, dynamicConfig.getInt(TradeProductKeys.EXPIRE_BATCH_SIZE,
                TradeProductKeys.DEFAULT_EXPIRE_BATCH_SIZE));
        long now = System.currentTimeMillis();
        List<OrderDO> candidates = readYourWrite(() -> orderMapper.selectExpired(now, batch * total));
        if (candidates.isEmpty()) {
            return 0;
        }
        Set<String> busy = inFlightOrderIds();
        int closed = 0;
        int skippedBusy = 0;
        int skippedCas = 0;
        Map<String, Long> closedAtById = new LinkedHashMap<>();
        for (OrderDO order : candidates) {
            if (ShardKeys.shardOf(order.getId(), total) != index) {
                continue;
            }
            ProductStateMachine.TimeoutAction action = productRouter.of(order.getProductType()).timeoutAction(order);
            if (action != ProductStateMachine.TimeoutAction.CLOSE_ORDER) {
                continue;
            }
            if (busy.contains(order.getId())) {
                skippedBusy++;
                log.info("超时关闭跳过（存在在途/成功支付单，防悬挂）orderId={}", order.getId());
                continue;
            }
            long closedAt = System.currentTimeMillis();
            int rows = orderMapper.closeExpired(order.getId(), closedAt);
            if (rows == 0) {
                skippedCas++;
                continue;
            }
            mirror("trade_order", order.getId());
            OrderExpireDO logRow = new OrderExpireDO();
            logRow.setId("OE" + order.getId());
            logRow.setOrderId(order.getId());
            logRow.setClosedAt(closedAt);
            logRow.setType("TIMEOUT");
            logRow.setCreatedAt(closedAt);
            try {
                expireMapper.insert(logRow);
            } catch (DuplicateKeyException e) {
                log.info("超时关闭留痕已存在（幂等）orderId={}", order.getId());
            }
            closedAtById.put(order.getId(), closedAt);
            closed++;
            MetricsSupport.count("order_expire_close", "type", "TIMEOUT");
            log.info("订单超时关闭 orderId={} expireAt={} closedAt={}", order.getId(), order.getExpireAt(), closedAt);
        }
        int reopened = guardRace(closedAtById);
        if (closed > 0 || skippedBusy > 0 || skippedCas > 0) {
            log.info("超时关闭扫描完成 shard={}/{} 候选={} 关闭={} 在途跳过={} CAS未命中={} 竞态回滚={}",
                    index, total, candidates.size(), closed, skippedBusy, skippedCas, reopened);
        }
        return closed - reopened;
    }

    /** 关闭后竞态复查：本批刚关的订单若出现在途支付单 → CAS 重开（防悬挂），返回回滚笔数。 */
    private int guardRace(Map<String, Long> closedAtById) {
        if (closedAtById.isEmpty()) {
            return 0;
        }
        Set<String> after = inFlightOrderIds();
        int reopened = 0;
        for (Map.Entry<String, Long> entry : closedAtById.entrySet()) {
            if (!after.contains(entry.getKey())) {
                continue;
            }
            int rows = orderMapper.reopenExpired(entry.getKey(), entry.getValue());
            if (rows > 0) {
                mirror("trade_order", entry.getKey());
                expireMapper.markReopened(entry.getKey(), System.currentTimeMillis());
                reopened++;
                MetricsSupport.count("order_expire_close", "type", "RACE_REOPEN");
                log.warn("超时关闭与支付受理竞态：已重开订单让支付收敛 orderId={} closedAt={}",
                        entry.getKey(), entry.getValue());
            }
        }
        return reopened;
    }

    /** 在途/成功支付单的订单集合（只调既有 PaymentFacade.listPayments，绝不新增跨域接口）。 */
    private Set<String> inFlightOrderIds() {
        Set<String> ids = new HashSet<>();
        try {
            List<PaymentDTO> payments = paymentFacade.listPayments().orThrow();
            for (PaymentDTO payment : payments) {
                if (("PROCESSING".equals(payment.getStatus()) || "SUCCESS".equals(payment.getStatus()))
                        && payment.getOrderId() != null) {
                    ids.add(payment.getOrderId());
                }
            }
        } catch (Exception e) {
            // 支付域不可达时 fail-closed：宁可不关，绝不误关在途单
            log.error("[告警] 超时关闭前置校验不可达（本轮不关闭任何订单）", e);
            MetricsSupport.count("order_expire_close", "type", "GUARD_UNAVAILABLE");
            throw e;
        }
        return ids;
    }

    /** 关闭留痕（只读，供控制台/verify-trade.py 断言不重不漏）。 */
    public List<OrderExpireDO> recent(int limit) {
        return expireMapper.selectRecent(limit <= 0 ? 50 : Math.min(limit, 500));
    }

    /** P2-H：留痕总量/竞态重开总量真值（窗口无关，增量断言单调可靠）。 */
    public long count() {
        return expireMapper.countAll();
    }

    public long countReopened() {
        return expireMapper.countReopened();
    }

    /** 到期候选（只读，不关闭）：供验证「分片不重不漏」与运维预览。 */
    public List<OrderDO> due(int shardIndex, int shardTotal, int limit) {
        int total = Math.max(1, shardTotal);
        int index = Math.max(0, Math.min(shardIndex, total - 1));
        long now = System.currentTimeMillis();
        List<OrderDO> out = new ArrayList<>();
        for (OrderDO order : readYourWrite(() -> orderMapper.selectExpired(now, Math.max(1, limit) * total))) {
            if (ShardKeys.shardOf(order.getId(), total) == index) {
                out.add(order);
            }
        }
        return out;
    }

    private void mirror(String table, Object pk) {
        ShardRowMirror mirror = shardRowMirror.getIfAvailable();
        if (mirror != null) {
            mirror.mirror(table, pk);
        }
    }

    private <T> T readYourWrite(Supplier<T> action) {
        ShardRuntime runtime = shardRuntime.getIfAvailable();
        return runtime == null ? action.get() : runtime.readYourWrite(action);
    }
}