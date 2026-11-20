package com.qoder.payment.trade.service;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.db.IdGenerator;
import com.qoder.payment.common.db.ShardRowMirror;
import com.qoder.payment.common.db.ShardRuntime;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.tcc.IdempotentTemplate;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.member.MemberFacade;
import com.qoder.payment.facade.trade.OrderCreateRequest;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.TradeTccContext;
import com.qoder.payment.trade.convert.OrderConverter;
import com.qoder.payment.trade.convert.OrderViews;
import com.qoder.payment.trade.dataobject.OrderDO;
import com.qoder.payment.trade.mapper.OrderMapper;
import com.qoder.payment.trade.product.ProductRouter;
import com.qoder.payment.trade.product.ProductStateMachine;
import com.qoder.payment.trade.product.TradeProduct;
import com.qoder.payment.trade.product.TradeProductKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;

/**
 * 交易单生命周期：CREATED → PAYING → PAID → (REFUNDED)；CREATED → CLOSED。
 * 状态流转全部以 CAS（WHERE status=旧态）保证并发安全。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeService {

    private final OrderMapper orderMapper;
    private final IdempotentTemplate idempotentTemplate;
    /** T-3 产品路由 + 出参装配（additive：INSTANT 钩子全空操作）。 */
    private final ProductRouter productRouter;
    private final OrderViews views;
    private final DynamicConfig dynamicConfig;
    /** P2-H T-5：合并单状态守卫（默认订单 mergeId 恒 null，零额外读写）。 */
    private final com.qoder.payment.trade.mapper.MergeOrderMapper mergeMapper;
    /** I-5b：分片未装配（local / enabled=false）时全部为 null，走改造前原路径。 */
    private final ObjectProvider<ShardRuntime> shardRuntime;
    private final ObjectProvider<IdGenerator> idGenerator;
    private final ObjectProvider<ShardRowMirror> shardRowMirror;

    @DubboReference
    private MemberFacade memberFacade;

    public OrderDTO createOrder(OrderCreateRequest request) {
        MoneyUtil.requireValid(request.getAmount());
        String subject = request.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > 128) {
            throw new BizException(ResultCode.PARAM_ERROR, "subject 必填且不超过 128 字");
        }
        MemberDTO merchant = memberFacade.get(request.getMerchantId()).orThrow();
        if (!"MERCHANT".equals(merchant.getType())) {
            throw new BizException(ResultCode.PARAM_ERROR, "下单会员必须是商户");
        }
        if (!"ACTIVE".equals(merchant.getStatus())) {
            throw new BizException(ResultCode.BIZ_ERROR, "商户已冻结: " + merchant.getId());
        }
        String outTradeNo = request.getOutTradeNo();
        if (outTradeNo != null && outTradeNo.isBlank()) {
            outTradeNo = null;
        }
        // I-5b：lambda 捕获需 effectively final（outTradeNo 上面被规范化过）
        final String outTradeNoKey = outTradeNo;
        // T-3 产品路由：缺省 INSTANT（零 RPC 增量、状态机钩子全空操作 ⇒ 默认产品行为逐字节不变）
        final TradeProduct product = productRouter.route(request, merchant);
        final ProductStateMachine machine = productRouter.machine(product);
        final String keyMerchantId = machine.keyMerchantId(request, merchant);
        if (outTradeNoKey != null) {
            OrderDO existing = readYourWrite(() -> orderMapper.selectByOutTradeNo(keyMerchantId, outTradeNoKey));
            if (existing != null) {
                assertSamePayee(existing, merchant, product);
                log.info("下单幂等命中 merchantId={} outTradeNo={} orderId={}", keyMerchantId, outTradeNoKey, existing.getId());
                return views.view(existing);
            }
        }
        OrderDO order = new OrderDO();
        order.setId(newOrderId());
        order.setMerchantId(merchant.getId());
        order.setMerchantName(merchant.getName());
        order.setOutTradeNo(outTradeNoKey);
        order.setBizType(request.getBizType() == null || request.getBizType().isBlank() ? "TRADE" : request.getBizType());
        order.setSubject(subject);
        order.setAmount(request.getAmount());
        order.setStatus("CREATED");
        order.setRefundedAmount(0L);
        order.setCreatedAt(System.currentTimeMillis());
        // T-3/T-4 additive：产品码 + 当前状态超时 deadline（只写新增列，既有列取值一字未改）
        order.setProductType(product.code());
        order.setExpireAt(expireAtOnCreate(request, order.getCreatedAt()));
        machine.onCreate(order, request, merchant);
        try {
            orderMapper.insert(order);
            mirror("trade_order", order.getId());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            OrderDO existing = readYourWrite(() -> orderMapper.selectByOutTradeNo(keyMerchantId, outTradeNoKey));
            if (existing == null) {
                throw e;
            }
            assertSamePayee(existing, merchant, product);
            return views.view(existing);
        }
        log.info("订单创建 orderId={} merchantId={} amount={} subject={} product={}",
                order.getId(), order.getMerchantId(), request.getAmount(), subject, product);
        return views.view(order);
    }

    /**
     * T-1 担保幂等守卫：担保单 merchant_id 是平台担保系统商户，真实卖家在 payee_merchant_id，
     * 故同 outTradeNo 跨卖家必须显式拒绝（绝不把 A 卖家的单返回给 B 卖家）。INSTANT 不受影响。
     */
    private static void assertSamePayee(OrderDO existing, MemberDTO seller, TradeProduct product) {
        if (product == TradeProduct.ESCROW && existing.getPayeeMerchantId() != null
                && !existing.getPayeeMerchantId().equals(seller.getId())) {
            throw new BizException(ResultCode.CONFLICT, "outTradeNo 已用于其它卖家的担保订单: "
                    + existing.getOutTradeNo() + " payee=" + existing.getPayeeMerchantId());
        }
    }

    /** T-4 超时策略：请求显式 expireMinutes 优先，否则 DynamicConfig（默认 30 分钟）；受理时快照落库。 */
    private long expireAtOnCreate(OrderCreateRequest request, long createdAt) {
        int minutes = dynamicConfig.getInt(TradeProductKeys.ORDER_EXPIRE_MINUTES,
                TradeProductKeys.DEFAULT_ORDER_EXPIRE_MINUTES);
        if (request.getExpireMinutes() != null) {
            minutes = request.getExpireMinutes();
        }
        if (minutes <= 0 || minutes > 525600) {
            throw new BizException(ResultCode.PARAM_ERROR, "expireMinutes 非法（1~525600）: " + minutes);
        }
        return createdAt + minutes * 60_000L;
    }

    public List<OrderDTO> listOrders() {
        return views.views(orderMapper.selectAll());
    }

    public OrderDTO getOrder(String orderId) {
        return views.view(require(orderId));
    }

    public OrderDTO closeOrder(String orderId) {
        OrderDO order = require(orderId);
        // P2-H T-5 additive 守卫：活跃合并单内子订单不可单独关闭（合并额守恒；默认订单 mergeId=null 零影响）
        if (order.getMergeId() != null) {
            com.qoder.payment.trade.dataobject.MergeOrderDO merge = mergeMapper.selectById(order.getMergeId());
            if (merge != null && ("CREATED".equals(merge.getStatus()) || "PAYING".equals(merge.getStatus()))) {
                throw new BizException(ResultCode.CONFLICT, "订单在合并单中，不可单独关闭: " + orderId);
            }
        }
        int closed = orderMapper.close(orderId, System.currentTimeMillis());
        mirror("trade_order", orderId);
        if (closed == 0) {
            throw new BizException(ResultCode.CONFLICT, "仅 CREATED 状态订单可关闭: " + orderId);
        }
        log.info("订单关闭 orderId={}", orderId);
        return views.view(require(orderId));
    }

    public OrderDTO refundMark(String orderId, long amount, String bizNo) {
        MoneyUtil.requireValid(amount);
        return idempotentTemplate.execute("REFUND_MARK", bizNo, OrderDTO.class, () -> {
            OrderDO order = require(orderId);
            if (!refundable(order.getStatus())) {
                throw new BizException(ResultCode.CONFLICT, "订单未支付成功，不可退款: " + orderId);
            }
            long refunded = order.getRefundedAmount() + amount;
            if (refunded > order.getAmount()) {
                throw new BizException(ResultCode.BIZ_ERROR, "退款金额超出已付金额: " + orderId);
            }
            String status = refunded == order.getAmount() ? "REFUNDED" : "PARTIAL_REFUNDED";
            orderMapper.updateRefund(orderId, refunded, status);
            mirror("trade_order", orderId);
            log.info("订单退款标记 orderId={} refunded={} status={}", orderId, refunded, status);
            return views.view(require(orderId));
        });
    }

    /** TCC Try（PAY）：CREATED → PAYING 预留订单。 */
    public void reserve(TradeTccContext ctx) {
        OrderDO order = require(ctx.getOrderId());
        if ("PAID".equals(order.getStatus()) || "REFUNDED".equals(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "订单已支付: " + ctx.getOrderId());
        }
        if ("CLOSED".equals(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "订单已关闭: " + ctx.getOrderId());
        }
        // T-3 产品守卫（INSTANT 空操作；PREAUTH 拒绝直接支付）
        ProductStateMachine machine = productRouter.of(order.getProductType());
        machine.checkPayable(order);
        // ==================== P2-H T-5/T-6 additive 守卫 ====================
        // 默认订单 stageCount/mergeId 恒 null、受理快照金额恒等于订单额 ⇒ 以下三段对 ride 路径零影响。
        // 分阶段订单必须走 payStage（防整单绕过阶段计划双付）
        if (order.getStageCount() != null && order.getStageCount() > 0) {
            throw new BizException(ResultCode.CONFLICT, "分阶段订单请使用分阶段付款(payStage): " + ctx.getOrderId());
        }
        // 活跃合并单内子订单必须走 payMerge（防拆分双付破坏 Σ子订单=合并额守恒）
        if (order.getMergeId() != null) {
            com.qoder.payment.trade.dataobject.MergeOrderDO merge = mergeMapper.selectById(order.getMergeId());
            if (merge != null && ("CREATED".equals(merge.getStatus()) || "PAYING".equals(merge.getStatus()))) {
                throw new BizException(ResultCode.CONFLICT, "订单在合并单中，请使用合并支付(payMerge): " + ctx.getOrderId());
            }
        }
        // T-6 改价竞态 CAS 伴随守卫：受理快照金额必须等于订单当前额（改价与受理双向竞态零悬挂）
        if (ctx.getAmount() != null && order.getAmount() != null
                && ctx.getAmount().longValue() != order.getAmount().longValue()) {
            throw new BizException(ResultCode.CONFLICT, "订单金额已变更（改价竞态），受理快照失效: " + ctx.getOrderId());
        }
        // T-4 受理时过期校验：仅在运维显式开启 trade.expire.reject-pay 时生效
        // （默认 false ⇒ 默认产品受理路径逐字节不变；关闭后不可支付由上面的 CLOSED 守卫保证）
        if (dynamicConfig.getBool(TradeProductKeys.EXPIRE_REJECT_PAY, false)
                && "CREATED".equals(order.getStatus()) && order.getExpireAt() != null
                && System.currentTimeMillis() > order.getExpireAt()) {
            throw new BizException(ResultCode.CONFLICT, "订单已超时关闭期限: " + ctx.getOrderId());
        }
        int reserved = orderMapper.reserve(ctx.getOrderId(), ctx.getPaymentId());
        mirror("trade_order", ctx.getOrderId());
        if (reserved == 0) {
            throw new BizException(ResultCode.CONFLICT, "订单正在支付中: " + ctx.getOrderId());
        }
        log.info("订单预留(TCC Try) orderId={} paymentId={}", ctx.getOrderId(), ctx.getPaymentId());
    }

    /** TCC Confirm（PAY）：PAYING → PAID。 */
    public void markPaid(TradeTccContext ctx) {
        OrderDO order = require(ctx.getOrderId());
        if ("PAID".equals(order.getStatus())) {
            return;
        }
        orderMapper.markPaid(ctx.getOrderId(), System.currentTimeMillis());
        mirror("trade_order", ctx.getOrderId());
        log.info("订单标记已付(TCC Confirm) orderId={}", ctx.getOrderId());
        // T-3 产品状态推进（INSTANT 完全不进本分支 ⇒ 默认产品零额外读写/零额外影子写；
        // ESCROW 置担保中 + 自动确认期限，只一次 CAS）
        if (TradeProduct.of(order.getProductType()) != TradeProduct.INSTANT) {
            OrderDO paid = readYourWrite(() -> orderMapper.selectById(ctx.getOrderId()));
            if (paid != null) {
                productRouter.of(paid.getProductType()).onPaid(paid);
                mirror("trade_order", ctx.getOrderId());
            }
        }
    }

    /** TCC Cancel（PAY）：PAYING → CREATED 释放预留。 */
    public void release(TradeTccContext ctx) {
        orderMapper.release(ctx.getOrderId(), ctx.getPaymentId());
        mirror("trade_order", ctx.getOrderId());
        log.info("订单释放预留(TCC Cancel) orderId={} paymentId={}", ctx.getOrderId(), ctx.getPaymentId());
    }

    /** TCC Try（REFUND）：校验可退额度，不落状态。 */
    public void checkRefundable(TradeTccContext ctx) {
        MoneyUtil.requireValid(ctx.getAmount());
        OrderDO order = require(ctx.getOrderId());
        if (!refundable(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "订单未支付成功，不可退款: " + ctx.getOrderId());
        }
        if (order.getRefundedAmount() + ctx.getAmount() > order.getAmount()) {
            throw new BizException(ResultCode.BIZ_ERROR, "退款金额超出已付金额: " + ctx.getOrderId());
        }
        // T-3 产品守卫（INSTANT 空操作；ESCROW 确认收货后禁止再从担保户退款，防挪用他单担保资金）
        productRouter.of(order.getProductType()).checkRefundable(order);
    }

    private static boolean refundable(String status) {
        return "PAID".equals(status) || "PARTIAL_REFUNDED".equals(status) || "REFUNDED".equals(status);
    }

    private OrderDO require(String orderId) {
        OrderDO order = readYourWrite(() -> orderMapper.selectById(orderId));
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND, "订单不存在: " + orderId);
        }
        return order;
    }

    // ------------------------------------------------------------------ I-5b 分片支撑

    /**
     * I-5b 订单取号：分片态走全局发号（雪花，杜绝各片 sequences 重号 E-7）；
     * 单库态（local / 一键回滚后）仍走 {@code next_value_for('seq_order')}。
     * <b>ID 形态不变</b>：始终 {@code O + 十进制数字}，长度 ≤ 20 &lt; VARCHAR(32)，存量 id 与 ride 双账本兼容。
     */
    private String newOrderId() {
        ShardRuntime runtime = shardRuntime.getIfAvailable();
        if (runtime != null && runtime.isActive()) {
            return idGenerator.getObject().next("O");
        }
        return "O" + orderMapper.nextId();
    }

    /** I-5b 全行双写：订单状态/payment_id 是可变列，整行真值同步影子侧，回滚后逐条一致。 */
    private void mirror(String table, Object pk) {
        ShardRowMirror mirror = shardRowMirror.getIfAvailable();
        if (mirror != null) {
            mirror.mirror(table, pk);
        }
    }

    /** I-5b 读己之写：强制走主库（未装配分片时零开销直接执行）。 */
    private <T> T readYourWrite(Supplier<T> action) {
        ShardRuntime runtime = shardRuntime.getIfAvailable();
        return runtime == null ? action.get() : runtime.readYourWrite(action);
    }
}