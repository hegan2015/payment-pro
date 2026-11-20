package com.qoder.payment.settle.service;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.accounting.PostRequest;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.payment.facade.channel.ChannelFacade;
import com.qoder.payment.facade.channel.ChannelTradeDTO;
import com.qoder.payment.facade.settle.WithdrawOrderDTO;
import com.qoder.payment.facade.settle.WithdrawRequest;
import com.qoder.payment.settle.bank.BankPayoutAdapter;
import com.qoder.payment.settle.mapper.WithdrawOrderMapper;
import com.qoder.tcc.IdempotentTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ST-4 提现到银行（结算侧出金，<b>沙箱渠道</b>）。
 *
 * <p><b>ride 红线隔离</b>：ride 的 {@code fund.withdraw} 仍走 {@code /openapi/**} →
 * {@code PaymentFacade.fundPay} 同步路径（D 账户 / C SYS_CASH，无渠道、无 WAITING、无复核闸门），
 * 语义与返回逐字节不变。本服务只服务新增的控制台端点 {@code /api/settle/withdraw}，
 * 不介入 payment 域任何既有路径。
 *
 * <p><b>状态机</b>：PROCESSING（受理 + 冻结在途）→ SUCCESS（银行到账）/ FAILED（失败已解冻退回）。
 * 终态 CAS（{@code WHERE status='PROCESSING'}）保证终态只翻一次，重复回执/补单被吸收。
 *
 * <p><b>账务三腿</b>（全部复式记账，D=C；bizNo 由 <b>requestId 确定性派生</b>，
 * 即便本地事务回滚后重试也绝不重复动账）：
 * <ul>
 *   <li>受理 {@code WH:<requestId>}：D 账户 total / C SYS_PAYOUT total —— 可用余额立即减少（冻结在途）；</li>
 *   <li>到账 {@code WP:<requestId>}：D SYS_PAYOUT total / C SYS_CASH amount + C SYS_FEE fee —— 备付金减少、手续费入 SYS_FEE；</li>
 *   <li>失败 {@code WR:<requestId>}：D SYS_PAYOUT total / C 账户 total —— <b>回滚解冻</b>，余额复原。</li>
 * </ul>
 * total = amount + fee，fee 缺省按 {@code settle.withdraw.fee-bps}（默认 0，与资金单 fee=0 口径一致）。
 *
 * <p>A-3 {@code AccountFacade.freezeAmount} 尚未落地（C 泳道），故冻结以「在途户 SYS_PAYOUT」
 * 等价实现（可用余额减少 + 全额可逆）；A-3 就绪后可平替为账务冻结腿。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawService {

    public static final String SYS_PAYOUT = "SYS_PAYOUT";
    public static final String SYS_CASH = "SYS_CASH";
    public static final String SYS_FEE = "SYS_FEE";
    public static final String KEY_FEE_BPS = "settle.withdraw.fee-bps";
    public static final int DEFAULT_FEE_BPS = 0;

    private final WithdrawOrderMapper withdrawMapper;
    private final BankPayoutAdapter bankPayoutAdapter;
    private final SettleSupport support;
    private final IdempotentTemplate idempotentTemplate;
    private final DynamicConfig dynamicConfig;

    @DubboReference
    private AccountingFacade accountingFacade;

    @DubboReference
    private AccountFacade accountFacade;

    @DubboReference
    private ChannelFacade channelFacade;

    public WithdrawOrderDTO withdraw(WithdrawRequest request) {
        require(request != null && notBlank(request.getRequestId()), "requestId 必填");
        require(request.getRequestId().length() <= 60, "requestId 需 ≤ 60 字符（账务幂等键派生）");
        require(notBlank(request.getAccountId()), "accountId 必填");
        require(request.getAmount() != null, "amount 必填");
        long amount = MoneyUtil.requireValid(request.getAmount());
        int feeBps = Math.max(0, dynamicConfig.getInt(KEY_FEE_BPS, DEFAULT_FEE_BPS));
        long fee = request.getFee() == null ? MoneyUtil.fee(amount, feeBps) : request.getFee();
        require(fee >= 0 && fee <= amount, "fee 需在 0~amount");
        long total = amount + fee;
        String requestId = request.getRequestId();
        String holdBizNo = "WH:" + requestId;

        return idempotentTemplate.execute("WITHDRAW_BANK", requestId, WithdrawOrderDTO.class, () -> {
            WithdrawOrderDTO existing = withdrawMapper.selectByRequestId(requestId);
            if (existing != null) {
                log.info("ST-4 提现幂等命中 requestId={} withdrawId={}", requestId, existing.getId());
                return existing;
            }
            AccountDTO account = accountFacade.get(request.getAccountId()).orThrow();
            require(account != null, "账户不存在: " + request.getAccountId());
            require("ACTIVE".equals(account.getStatus()), "账户已冻结: " + account.getId());
            long balance = SettleSupport.nz(accountingFacade.balance(account.getId()).orThrow());
            require(balance >= total, "账户可用余额不足: " + account.getId() + " balance=" + balance + " need=" + total);

            long now = System.currentTimeMillis();
            WithdrawOrderDTO order = new WithdrawOrderDTO();
            order.setId(support.newId("W"));
            order.setAccountId(account.getId());
            order.setMerchantId(account.getMemberId());
            order.setAmount(amount);
            order.setFee(fee);
            order.setBankCardNo(request.getBankCardNo());
            order.setChannelCode(bankPayoutAdapter.code());
            order.setStatus("PROCESSING");
            order.setRequestId(requestId);
            order.setBizNo(holdBizNo);
            order.setReconcileCount(0);
            order.setCreatedAt(now);
            order.setUpdatedAt(now);
            withdrawMapper.insert(order);

            // 受理即冻结在途：可用余额减少（同步先扣，与资金单语义一致）
            post(holdBizNo, "提现受理-冻结在途", order.getId(),
                    List.of(line(account.getId(), "D", total), line(SYS_PAYOUT, "C", total)));
            log.info("ST-4 提现受理 withdrawId={} accountId={} amount={} fee={} requestId={}",
                    order.getId(), account.getId(), amount, fee, requestId);

            BankPayoutAdapter.PayoutResult result = bankPayoutAdapter.submit(command(order));
            if ("SUCCESS".equals(result.status())) {
                return applyTerminal(order, "SUCCESS", result.channelOrderNo(), null);
            }
            if ("FAILED".equals(result.status())) {
                return applyTerminal(order, "FAILED", result.channelOrderNo(), result.reason());
            }
            withdrawMapper.casTerminal(order.getId(), "PROCESSING", result.channelOrderNo(), null,
                    System.currentTimeMillis());
            return require(withdrawMapper.selectById(order.getId()));
        });
    }

    public WithdrawOrderDTO get(String withdrawId) {
        return support.readYourWrite(() -> require(withdrawMapper.selectById(withdrawId)));
    }

    public List<WithdrawOrderDTO> list(String status) {
        return support.readYourWrite(() -> status == null || status.isBlank()
                ? withdrawMapper.selectAll() : withdrawMapper.selectByStatus(status));
    }

    /** 出金补单：先查渠道域（只读 facade），无渠道交易则查沙箱适配器；终态 CAS + 幂等动账。 */
    public WithdrawOrderDTO reconcile(String withdrawId) {
        WithdrawOrderDTO order = get(withdrawId);
        withdrawMapper.bumpReconcile(order.getId(), System.currentTimeMillis());
        if (!"PROCESSING".equals(order.getStatus())) {
            return get(withdrawId);
        }
        String channelStatus = queryChannel(order.getBizNo());
        if ("CAPTURED".equals(channelStatus)) {
            return applyTerminal(order, "SUCCESS", null, null);
        }
        if ("FAILED".equals(channelStatus)) {
            return applyTerminal(order, "FAILED", null, "渠道出款失败（补单收敛）");
        }
        BankPayoutAdapter.PayoutResult result = bankPayoutAdapter.query(command(order));
        if ("SUCCESS".equals(result.status())) {
            return applyTerminal(order, "SUCCESS", result.channelOrderNo(), null);
        }
        if ("FAILED".equals(result.status())) {
            return applyTerminal(order, "FAILED", result.channelOrderNo(), result.reason());
        }
        log.info("ST-4 出金补单仍在途 withdrawId={} reconcileCount={}", order.getId(), order.getReconcileCount());
        return get(withdrawId);
    }

    /** 沙箱银行回执（等价渠道异步回执）：只接受终态，重复/乱序被 CAS 吸收。 */
    public WithdrawOrderDTO receipt(String withdrawId, String status, String reason) {
        require("SUCCESS".equals(status) || "FAILED".equals(status), "回执 status 仅接受 SUCCESS/FAILED");
        WithdrawOrderDTO order = get(withdrawId);
        if (!"PROCESSING".equals(order.getStatus())) {
            log.info("ST-4 回执幂等吸收 withdrawId={} current={} receipt={}", withdrawId, order.getStatus(), status);
            return order;
        }
        return applyTerminal(order, status, "SBX-" + order.getBizNo(), reason);
    }

    /** 终态落地：先幂等动账，再 CAS 翻状态（任一顺序失败重试都不会重复动账）。 */
    private WithdrawOrderDTO applyTerminal(WithdrawOrderDTO order, String status, String channelOrderNo,
                                           String reason) {
        long total = order.getAmount() + order.getFee();
        if ("SUCCESS".equals(status)) {
            List<TransferLineDTO> lines = new ArrayList<>();
            lines.add(line(SYS_PAYOUT, "D", total));
            lines.add(line(SYS_CASH, "C", order.getAmount()));
            if (order.getFee() > 0) {
                lines.add(line(SYS_FEE, "C", order.getFee()));
            }
            post("WP:" + order.getRequestId(), "提现到账-备付金出账", order.getId(), lines);
        } else {
            post("WR:" + order.getRequestId(), "提现失败-解冻退回", order.getId(),
                    List.of(line(SYS_PAYOUT, "D", total), line(order.getAccountId(), "C", total)));
        }
        int updated = withdrawMapper.casTerminal(order.getId(), status, channelOrderNo, reason,
                System.currentTimeMillis());
        log.info("ST-4 提现终态 withdrawId={} status={} casUpdated={} amount={} fee={} reason={}",
                order.getId(), status, updated, order.getAmount(), order.getFee(), reason);
        return get(order.getId());
    }

    /** 只读调渠道 facade；渠道无此笔（沙箱出款未落 channel_trade）时返回 null，不臆断失败。 */
    private String queryChannel(String bizNo) {
        try {
            Result<ChannelTradeDTO> result = channelFacade.queryChannelTrade(bizNo);
            if (result != null && result.isSuccess() && result.getData() != null) {
                return result.getData().getStatus();
            }
        } catch (Exception e) {
            log.warn("ST-4 渠道查询不可达（降级沙箱判定）bizNo={}: {}", bizNo, e.getMessage());
        }
        return null;
    }

    private BankPayoutAdapter.PayoutCommand command(WithdrawOrderDTO order) {
        return new BankPayoutAdapter.PayoutCommand(order.getBizNo(), order.getAccountId(), order.getAmount(),
                order.getBankCardNo(), order.getCreatedAt() == null ? System.currentTimeMillis() : order.getCreatedAt());
    }

    private void post(String bizNo, String subject, String refId, List<TransferLineDTO> lines) {
        PostRequest request = new PostRequest();
        request.setBizNo(bizNo);
        request.setSubject(subject);
        request.setRefId(refId);
        request.setLines(lines);
        accountingFacade.post(request).orThrow();
    }

    private WithdrawOrderDTO require(WithdrawOrderDTO order) {
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND, "提现单不存在");
        }
        return order;
    }

    private static TransferLineDTO line(String accountId, String dir, long amount) {
        TransferLineDTO line = new TransferLineDTO();
        line.setAccountId(accountId);
        line.setDir(dir);
        line.setAmount(amount);
        return line;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new BizException(ResultCode.PARAM_ERROR, message);
        }
    }
}