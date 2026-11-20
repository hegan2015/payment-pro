package com.qoder.payment.trade.service;

import com.qoder.payment.common.db.ShardRowMirror;
import com.qoder.payment.common.db.ShardRuntime;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.account.AccountFreezeDTO;
import com.qoder.payment.facade.account.FreezeRequest;
import com.qoder.payment.facade.account.UnfreezeRequest;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.accounting.PostRequest;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.trade.convert.OrderViews;
import com.qoder.payment.trade.dataobject.OrderDO;
import com.qoder.payment.trade.dataobject.PreAuthCompleteDO;
import com.qoder.payment.trade.dataobject.PreAuthRecordDO;
import com.qoder.payment.trade.mapper.OrderMapper;
import com.qoder.payment.trade.mapper.PreAuthMapper;
import com.qoder.payment.trade.product.TradeProduct;
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
 * T-2 预授权编排（押金）：冻结 → 完成扣款（≤冻结额，差额解冻）/ 撤销全额解冻。
 *
 * <p><b>冻结能力完全复用 account A-3</b>（只调 {@code AccountFacade.freezeAmount/unfreezeAmount}，
 * reasonType=PREAUTH）：经账务 TCC Try/Cancel 落 {@code account_balance.frozen}，
 * <b>available=balance−frozen 口径不变、balance 不动、不产生分录</b> ⇒ 试算平衡不受冻结影响。
 *
 * <p><b>完成扣款</b>：A-3 解冻按整单释放（facade 无部分解冻语义），故一次完成 = ①整单解冻
 * → ②差额重冻（剩余冻结额，新 bizNo）→ ③账务 post D 买家 / C 卖家（完成额，押金扣款不计手续费）。
 * 三步远端幂等键全部由 {@code (orderId, 已完成额快照)} 确定性派生：
 * 同额重试收敛（A-3 bizNo 幂等 + 账务 VoucherGuard），异额重试被断言拒绝（fail-closed，绝不重扣/超扣）。
 *
 * <p><b>幂等与竞态</b>：调用方 requestId 经 {@code (PREAUTH_COMPLETE,requestId)} 幂等模板；
 * 累计额推进用 CAS（{@code completed_amount=期望值 AND 剩余额度≥本次额}）⇒ 并发完成只有一个成功，
 * 超扣一律拒绝。撤销只释放剩余额度，已完成扣款不回滚。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreAuthService {

    private static final String REASON = "PREAUTH";
    private static final String OPERATOR = "trade";

    private final OrderMapper orderMapper;
    private final PreAuthMapper preAuthMapper;
    private final OrderViews views;
    private final IdempotentTemplate idempotentTemplate;
    private final ObjectProvider<ShardRuntime> shardRuntime;
    private final ObjectProvider<ShardRowMirror> shardRowMirror;

    @DubboReference
    private AccountFacade accountFacade;

    @DubboReference
    private AccountingFacade accountingFacade;

    /** 预授权冻结（冻结额 = 订单金额）。 */
    public OrderDTO preAuth(String orderId, String accountId, String requestId) {
        if (accountId == null || accountId.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "accountId 必填");
        }
        String idemKey = requestId == null || requestId.isBlank() ? "PAF:" + orderId : requestId;
        return idempotentTemplate.execute("PREAUTH_FREEZE", idemKey, OrderDTO.class, () -> {
            OrderDO order = requireProduct(orderId);
            PreAuthRecordDO existing = preAuthMapper.selectByOrderId(orderId);
            if (existing != null) {
                if (!accountId.equals(existing.getAccountId())) {
                    throw new BizException(ResultCode.CONFLICT, "预授权已冻结于其它账户: " + existing.getAccountId());
                }
                log.info("预授权冻结幂等命中 orderId={} freezeId={}", orderId, existing.getFreezeId());
                return views.view(order);
            }
            if (!"CREATED".equals(order.getStatus())) {
                throw new BizException(ResultCode.CONFLICT, "订单状态不可预授权: " + order.getStatus());
            }
            MoneyUtil.requireValid(order.getAmount());
            AccountDTO payer = accountFacade.get(accountId).orThrow();
            if (!"ACTIVE".equals(payer.getStatus())) {
                throw new BizException(ResultCode.BIZ_ERROR, "付款账户已冻结: " + accountId);
            }
            // A-3 冻结（经账务 TCC Try）：frozen+=amount，balance 不动
            AccountFreezeDTO freeze = accountFacade.freezeAmount(freeze(accountId, order.getAmount(),
                    "预授权-" + orderId, "PAF:" + orderId)).orThrow();
            long now = System.currentTimeMillis();
            PreAuthRecordDO record = new PreAuthRecordDO();
            record.setId("PA" + preAuthMapper.nextRecordId());
            record.setOrderId(orderId);
            record.setAccountId(accountId);
            record.setAuthAmount(order.getAmount());
            record.setCompletedAmount(0L);
            record.setStatus("AUTHORIZED");
            record.setFreezeId(freeze.getId());
            record.setRequestId(idemKey);
            record.setCreatedAt(now);
            record.setUpdatedAt(now);
            try {
                preAuthMapper.insertRecord(record);
            } catch (DuplicateKeyException e) {
                log.info("预授权单已存在（并发幂等）orderId={}", orderId);
            }
            orderMapper.markAuthorized(orderId);
            mirror("trade_order", orderId);
            MetricsSupport.count("preauth_freeze", "status", "success");
            log.info("预授权冻结 orderId={} accountId={} amount={} freezeId={}",
                    orderId, accountId, order.getAmount(), freeze.getId());
            return views.view(require(orderId));
        });
    }

    /** 预授权完成扣款（amount ≤ 冻结额 − 已完成额；差额解冻）。 */
    public OrderDTO complete(String orderId, long amount, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "requestId 必填（幂等键）");
        }
        MoneyUtil.requireValid(amount);
        return idempotentTemplate.execute("PREAUTH_COMPLETE", requestId, OrderDTO.class, () -> {
            OrderDO order = requireProduct(orderId);
            PreAuthRecordDO record = preAuthMapper.selectByOrderId(orderId);
            if (record == null) {
                throw new BizException(ResultCode.CONFLICT, "订单未预授权冻结: " + orderId);
            }
            if ("CANCELLED".equals(record.getStatus()) || "COMPLETED".equals(record.getStatus())) {
                throw new BizException(ResultCode.CONFLICT, "预授权已终态，不可完成: " + record.getStatus());
            }
            long expected = record.getCompletedAmount() == null ? 0L : record.getCompletedAmount();
            long remaining = record.getAuthAmount() - expected;
            if (amount > remaining) {
                throw new BizException(ResultCode.BIZ_ERROR,
                        "完成金额超出剩余冻结额: 剩余=" + remaining + " 请求=" + amount);
            }
            long after = expected + amount;
            String status = after == record.getAuthAmount() ? "COMPLETED" : "PARTIAL_COMPLETED";
            long now = System.currentTimeMillis();
            int cas = preAuthMapper.advanceComplete(orderId, expected, amount, status, now);
            if (cas == 0) {
                throw new BizException(ResultCode.CONFLICT, "预授权完成竞态（累计额已变/额度不足），请重读后重试: " + orderId);
            }
            // 确定性远端幂等键：同额重试收敛，异额重试被 A-3 assertSame / 账务 VoucherGuard 拒绝
            String unfreezeBizNo = "PAU:" + orderId + ":" + expected;
            String postBizNo = "PAP:" + orderId + ":" + expected;
            String refreezeBizNo = "PAR:" + orderId + ":" + expected;
            String completeId = "PC" + preAuthMapper.nextCompleteId();
            PreAuthCompleteDO logRow = new PreAuthCompleteDO();
            logRow.setId(completeId);
            logRow.setOrderId(orderId);
            logRow.setAmount(amount);
            logRow.setBizNo(postBizNo);
            logRow.setRequestId(requestId);
            logRow.setReleasedFreezeId(record.getFreezeId());
            logRow.setStatus("PENDING");
            logRow.setCreatedAt(now);
            try {
                preAuthMapper.insertComplete(logRow);
            } catch (DuplicateKeyException e) {
                log.info("预授权完成留痕已存在（幂等）requestId={}", requestId);
            }
            // ① 整单解冻（A-3 无部分解冻语义）
            if (record.getFreezeId() != null) {
                accountFacade.unfreezeAmount(unfreeze(record.getFreezeId(), unfreezeBizNo)).orThrow();
            }
            // ② 差额重冻：剩余冻结额继续冻结（可用余额口径不变）
            long remainder = record.getAuthAmount() - after;
            String newFreezeId = null;
            if (remainder > 0) {
                AccountFreezeDTO refreeze = accountFacade.freezeAmount(freeze(record.getAccountId(), remainder,
                        "预授权差额重冻-" + orderId, refreezeBizNo)).orThrow();
                newFreezeId = refreeze.getId();
            }
            // ③ 完成扣款过账：D 买家 / C 卖家（押金扣款，不计手续费）
            AccountDTO payee = accountFacade.getByMember(order.getMerchantId()).orThrow();
            accountingFacade.post(post(postBizNo, orderId, "预授权完成-" + orderId,
                    List.of(line(record.getAccountId(), "D", amount), line(payee.getId(), "C", amount)))).orThrow();
            preAuthMapper.updateFreeze(orderId, newFreezeId, System.currentTimeMillis());
            preAuthMapper.finishComplete(completeId, "DONE", newFreezeId);
            if ("COMPLETED".equals(status)) {
                orderMapper.markPreAuthFinal(orderId, "COMPLETED");
            }
            mirror("trade_order", orderId);
            MetricsSupport.count("preauth_complete", "status", "success");
            log.info("预授权完成 orderId={} amount={} 累计={}/{} 差额重冻={} bizNo={}",
                    orderId, amount, after, record.getAuthAmount(), remainder, postBizNo);
            return views.view(require(orderId));
        });
    }

    /** 预授权撤销：剩余冻结额全额解冻（已完成扣款不回滚）。 */
    public OrderDTO cancel(String orderId, String requestId) {
        String idemKey = requestId == null || requestId.isBlank() ? "PAX:" + orderId : requestId;
        return idempotentTemplate.execute("PREAUTH_CANCEL", idemKey, OrderDTO.class, () -> {
            OrderDO order = requireProduct(orderId);
            PreAuthRecordDO record = preAuthMapper.selectByOrderId(orderId);
            if (record == null) {
                throw new BizException(ResultCode.CONFLICT, "订单未预授权冻结: " + orderId);
            }
            if ("CANCELLED".equals(record.getStatus())) {
                log.info("预授权撤销幂等命中 orderId={}", orderId);
                return views.view(order);
            }
            if ("COMPLETED".equals(record.getStatus())) {
                throw new BizException(ResultCode.CONFLICT, "预授权已全额完成，无可撤销冻结额: " + orderId);
            }
            long remaining = record.getAuthAmount() - (record.getCompletedAmount() == null ? 0L : record.getCompletedAmount());
            int cas = preAuthMapper.cancelRecord(orderId, System.currentTimeMillis());
            if (cas == 0) {
                return views.view(require(orderId));
            }
            if (remaining > 0 && record.getFreezeId() != null) {
                accountFacade.unfreezeAmount(unfreeze(record.getFreezeId(), "PAX:" + orderId)).orThrow();
            }
            orderMapper.markPreAuthFinal(orderId, "CANCELLED");
            mirror("trade_order", orderId);
            MetricsSupport.count("preauth_cancel", "status", "success");
            log.info("预授权撤销 orderId={} 解冻剩余额={} freezeId={}", orderId, remaining, record.getFreezeId());
            return views.view(require(orderId));
        });
    }

    /** 预授权完成留痕（只读）。 */
    public List<PreAuthCompleteDO> completes(String orderId) {
        return preAuthMapper.selectCompletes(orderId);
    }

    private OrderDO requireProduct(String orderId) {
        OrderDO order = readYourWrite(() -> orderMapper.selectById(orderId));
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND, "订单不存在: " + orderId);
        }
        if (TradeProduct.PREAUTH != TradeProduct.of(order.getProductType())) {
            throw new BizException(ResultCode.PARAM_ERROR, "非预授权订单: " + orderId);
        }
        return order;
    }

    private OrderDO require(String orderId) {
        OrderDO order = readYourWrite(() -> orderMapper.selectById(orderId));
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND, "订单不存在: " + orderId);
        }
        return order;
    }

    private static FreezeRequest freeze(String accountId, long amount, String reason, String bizNo) {
        FreezeRequest request = new FreezeRequest();
        request.setAccountId(accountId);
        request.setAmount(amount);
        request.setReasonType(REASON);
        request.setReason(reason);
        request.setOperator(OPERATOR);
        request.setBizNo(bizNo);
        return request;
    }

    private static UnfreezeRequest unfreeze(String freezeId, String bizNo) {
        UnfreezeRequest request = new UnfreezeRequest();
        request.setFreezeId(freezeId);
        request.setReasonType(REASON);
        request.setOperator(OPERATOR);
        request.setBizNo(bizNo);
        return request;
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