package com.qoder.payment.trade.service;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.db.ShardKeys;
import com.qoder.payment.common.db.ShardRowMirror;
import com.qoder.payment.common.db.ShardRuntime;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.accounting.PostRequest;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.facade.payment.PaymentFacade;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.trade.convert.OrderViews;
import com.qoder.payment.trade.dataobject.EscrowConfirmDO;
import com.qoder.payment.trade.dataobject.OrderDO;
import com.qoder.payment.trade.mapper.EscrowConfirmMapper;
import com.qoder.payment.trade.mapper.OrderMapper;
import com.qoder.payment.trade.product.TradeProduct;
import com.qoder.payment.trade.product.TradeProductKeys;
import com.qoder.tcc.IdempotentTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * T-1 担保交易编排（确认收货 / 超时自动确认）。
 *
 * <p><b>资金流</b>（全程复式记账，交易域只调账务/账户/支付既有 facade）：
 * <ol>
 *   <li>买家付款：支付核心既有 TCC 模板 D 买家 / C 担保户(净额) + C SYS_FEE(手续费)
 *       —— 担保单 merchantId=平台担保系统商户，故卖家 <b>确认前分文未入</b>；</li>
 *   <li>确认收货：{@code AccountingFacade.post} D 担保户 / C 卖家（净额），
 *       bizNo={@code EC+orderId} 确定性 ⇒ 账务 VoucherGuard + (ESCROW_CONFIRM,orderId) 双层幂等；</li>
 *   <li>确认前退款：走支付核心既有 refund（D 担保户 / D SYS_FEE / C 买家），
 *       确认后由 {@code EscrowProduct.checkRefundable} 在 TCC Try 阶段拒绝（防挪用他单担保资金）。</li>
 * </ol>
 *
 * <p><b>净额口径</b>：net = (已付 − 已退) − (手续费 − 已退手续费)，与担保户实际留存逐分一致；
 * 每笔确认借贷相等 ⇒ 试算 D=C、Σbalance=0 不回归，担保户余额 = 未确认担保净额之和。
 *
 * <p><b>并发/幂等</b>：escrow_status CAS（HOLD→CONFIRMED/AUTO_CONFIRMED 只一次）+ 幂等模板占位；
 * 远端过账失败则本地事务回滚（CAS 复原），重试同 bizNo 收敛，绝不重复打款。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EscrowService {

    private final OrderMapper orderMapper;
    private final EscrowConfirmMapper confirmMapper;
    private final OrderViews views;
    private final EscrowHoldAccountService holdAccounts;
    private final IdempotentTemplate idempotentTemplate;
    private final DynamicConfig dynamicConfig;
    private final ObjectProvider<ShardRuntime> shardRuntime;
    private final ObjectProvider<ShardRowMirror> shardRowMirror;

    @DubboReference
    private AccountingFacade accountingFacade;

    @DubboReference
    private AccountFacade accountFacade;

    @DubboReference
    private PaymentFacade paymentFacade;

    /** 确认收货（手动，MANUAL）。 */
    public OrderDTO confirmReceive(String orderId) {
        return confirm(orderId, "MANUAL", null);
    }

    public OrderDTO confirm(String orderId, String type, String operator) {
        return idempotentTemplate.execute("ESCROW_CONFIRM", orderId, OrderDTO.class, () -> {
            OrderDO order = require(orderId);
            if (TradeProduct.ESCROW != TradeProduct.of(order.getProductType())) {
                throw new BizException(ResultCode.PARAM_ERROR, "非担保订单，不可确认收货: " + orderId);
            }
            if (order.getEscrowStatus() != null && !"HOLD".equals(order.getEscrowStatus())) {
                log.info("担保确认收货幂等命中 orderId={} escrowStatus={}", orderId, order.getEscrowStatus());
                return views.view(order);
            }
            if (!"PAID".equals(order.getStatus()) && !"PARTIAL_REFUNDED".equals(order.getStatus())) {
                throw new BizException(ResultCode.CONFLICT, "担保订单未支付成功，不可确认收货: " + orderId
                        + " status=" + order.getStatus());
            }
            if (order.getPaymentId() == null || order.getPaymentId().isBlank()) {
                throw new BizException(ResultCode.CONFLICT, "担保订单缺少支付单: " + orderId);
            }
            PaymentDTO payment = paymentFacade.getPayment(order.getPaymentId()).orThrow();
            long net = (payment.getAmount() - nz(payment.getRefundedAmount()))
                    - (nz(payment.getFee()) - nz(payment.getRefundedFee()));
            if (net <= 0) {
                throw new BizException(ResultCode.BIZ_ERROR, "担保净额为 0（已全额退款），无需确认收货: " + orderId);
            }
            EscrowHoldAccountService.Hold hold = holdAccounts.ensure();
            AccountDTO payee = accountFacade.getByMember(order.getPayeeMerchantId()).orThrow();
            String toStatus = "AUTO".equals(type) ? "AUTO_CONFIRMED" : "CONFIRMED";
            int cas = orderMapper.confirmEscrow(orderId, toStatus);
            mirror("trade_order", orderId);
            if (cas == 0) {
                log.info("担保确认收货 CAS 未命中（并发已确认）orderId={}", orderId);
                return views.view(require(orderId));
            }
            String bizNo = "EC" + orderId;
            accountingFacade.post(post(bizNo, orderId, "担保确认收货-" + orderId,
                    List.of(line(hold.accountId(), "D", net), line(payee.getId(), "C", net)))).orThrow();
            EscrowConfirmDO logRow = new EscrowConfirmDO();
            logRow.setId(bizNo);
            logRow.setOrderId(orderId);
            logRow.setPaymentId(payment.getId());
            logRow.setAmount(net);
            logRow.setFee(nz(payment.getFee()) - nz(payment.getRefundedFee()));
            logRow.setType(type);
            logRow.setBizNo(bizNo);
            logRow.setHoldAccountId(hold.accountId());
            logRow.setPayeeAccountId(payee.getId());
            logRow.setOperator(operator);
            logRow.setCreatedAt(System.currentTimeMillis());
            try {
                confirmMapper.insert(logRow);
            } catch (DuplicateKeyException e) {
                log.info("担保确认留痕已存在（幂等）orderId={}", orderId);
            }
            MetricsSupport.count("escrow_confirm", "type", type);
            log.info("担保确认收货 orderId={} type={} net={} 担保户={} 卖家={} bizNo={}",
                    orderId, type, net, hold.accountId(), payee.getId(), bizNo);
            return views.view(require(orderId));
        });
    }

    /**
     * T-1 超时自动确认扫描（JobHandler {@code escrowAutoConfirm} 与运维入口共用）：
     * 按 orderId CRC32 分片（与 DB 分片同一算法族）⇒ 多实例/多分片不重不漏；逐单幂等确认。
     */
    public int autoConfirmDue(int shardIndex, int shardTotal) {
        int total = Math.max(1, shardTotal);
        int index = Math.max(0, Math.min(shardIndex, total - 1));
        int batch = Math.max(1, dynamicConfig.getInt(TradeProductKeys.ESCROW_BATCH_SIZE,
                TradeProductKeys.DEFAULT_ESCROW_BATCH_SIZE));
        long now = System.currentTimeMillis();
        // 取批量放大到 batch*total：各片只处理自己那份，避免「前 N 行全属他片」导致本片漏处理
        List<OrderDO> due = readYourWrite(() -> orderMapper.selectEscrowDue(now, batch * total));
        int confirmed = 0;
        for (OrderDO order : due) {
            if (ShardKeys.shardOf(order.getId(), total) != index) {
                continue;
            }
            try {
                confirm(order.getId(), "AUTO", "system");
                confirmed++;
            } catch (Exception e) {
                MetricsSupport.count("escrow_auto_confirm", "status", "fail");
                log.error("[告警] 担保超时自动确认失败 orderId={} shard={}/{}", order.getId(), index, total, e);
            }
        }
        if (confirmed > 0) {
            log.info("担保超时自动确认完成 shard={}/{} 候选={} 确认={}", index, total, due.size(), confirmed);
        }
        MetricsSupport.count("escrow_auto_confirm", "status", "success");
        return confirmed;
    }

    /** 担保确认留痕（只读，供控制台/验证）。 */
    public List<EscrowConfirmDO> recentConfirms(int limit) {
        return confirmMapper.selectRecent(limit <= 0 ? 50 : Math.min(limit, 500));
    }

    /** P2-H：确认留痕总量真值（窗口无关）。 */
    public long countConfirms() {
        return confirmMapper.countAll();
    }

    public long countAutoConfirms() {
        return confirmMapper.countByType("AUTO");
    }

    private OrderDO require(String orderId) {
        OrderDO order = readYourWrite(() -> orderMapper.selectById(orderId));
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND, "订单不存在: " + orderId);
        }
        return order;
    }

    private static PostRequest post(String bizNo, String refId, String subject, List<TransferLineDTO> lines) {
        PostRequest request = new PostRequest();
        request.setBizNo(bizNo);
        request.setRefId(refId);
        request.setSubject(subject);
        request.setLines(new ArrayList<>(lines));
        return request;
    }

    private static TransferLineDTO line(String accountId, String dir, long amount) {
        TransferLineDTO line = new TransferLineDTO();
        line.setAccountId(accountId);
        line.setDir(dir);
        line.setAmount(amount);
        return line;
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
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