package com.qoder.payment.member.openplatform;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.openplatform.SandboxAccountDTO;
import com.qoder.payment.facade.openplatform.SandboxOrderDTO;
import com.qoder.payment.facade.openplatform.SandboxTxnDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * G-4a 沙箱环境账本（<b>完全独立于生产资金</b>）。
 *
 * <p>隔离证据链：
 * <ul>
 *   <li>只写 openapi_sandbox_account / openapi_sandbox_txn / openapi_sandbox_order 三张新表；</li>
 *   <li><b>不注入</b> AccountFacade/AccountingFacade/PaymentFacade/TradeFacade —— 结构上不可能改动生产账；</li>
 *   <li>id 前缀 SBXA/SBXM/SBXO/SBXP 与生产 M/A/O/P 命名空间不重叠；</li>
 *   <li>所有方法强制校验 appId 已注册且 sandbox=1，生产 appId 无法读写沙箱账本（反之亦然）。</li>
 * </ul>
 *
 * <p>语义对齐 v1 冻结面：资金单 fee=0、同步 SUCCESS、bizNo 幂等；trade.pay 仅 BALANCE 同步分支；
 * account.balance 返回裸 long。沙箱不产生真实资金变动，也不产生渠道调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxService {

    private static final long MAX_AMOUNT = 1_000_000_000_000L;
    private static final String MERCHANT_ACCOUNT_PREFIX = "SBX-MERCHANT-";

    private final SandboxMapper sandboxMapper;
    private final OpenAppMapper openAppMapper;

    @Transactional
    public SandboxAccountDTO openAccount(String appId, String name, String type) {
        OpenAppDO app = requireSandbox(appId);
        if (name == null || name.isBlank() || name.length() > 64) {
            throw new BizException(ResultCode.PARAM_ERROR, "name 必填且不超过 64 字");
        }
        String accountType = type == null || type.isBlank() ? "USER" : type;
        SandboxAccountDO existing = sandboxMapper.selectAccountByName(appId, name, accountType);
        if (existing != null) {
            return toAccount(existing);
        }
        long seq = sandboxMapper.nextAccountId();
        SandboxAccountDO account = new SandboxAccountDO();
        account.setAccountId("SBXA" + seq);
        account.setMemberId("SBXM" + seq);
        account.setAppId(app.getAppId());
        account.setName(name);
        account.setType(accountType);
        account.setBalance(0L);
        account.setCreatedAt(System.currentTimeMillis());
        sandboxMapper.insertAccount(account);
        log.info("沙箱开户 appId={} accountId={} name={}", appId, account.getAccountId(), name);
        return toAccount(account);
    }

    public long balance(String appId, String accountId) {
        requireSandbox(appId);
        return requireAccount(appId, accountId).getBalance();
    }

    public List<SandboxAccountDTO> listAccounts(String appId) {
        requireSandbox(appId);
        return sandboxMapper.selectAccounts(appId).stream().map(this::toAccount).toList();
    }

    /** UI-B additive：沙箱交易单列表（只读，倒序；limit 默认 50，上限 200）。 */
    public List<SandboxOrderDTO> listOrders(String appId, Integer limit) {
        requireSandbox(appId);
        return sandboxMapper.selectOrders(appId, pageLimit(limit)).stream().map(this::toOrder).toList();
    }

    /** UI-B additive：沙箱资金单/支付单列表（只读，倒序；limit 默认 50，上限 200）。 */
    public List<SandboxTxnDTO> listTxns(String appId, Integer limit) {
        requireSandbox(appId);
        return sandboxMapper.selectTxns(appId, pageLimit(limit)).stream().map(this::toTxn).toList();
    }

    private static int pageLimit(Integer limit) {
        int value = limit == null ? 50 : limit;
        return Math.max(1, Math.min(200, value));
    }

    /** 沙箱充值/提现资金单（fee=0、同步 SUCCESS、bizNo 幂等）。 */
    @Transactional
    public SandboxTxnDTO fund(String appId, String kind, String accountId, Long amount, String bizNo) {
        requireSandbox(appId);
        long value = amount(amount);
        String bizType = "TOPUP".equalsIgnoreCase(kind) ? "TOPUP" : "WITHDRAW";
        String requestId = required(bizNo, "bizNo");
        SandboxTxnDO existing = sandboxMapper.selectTxnByRequest(appId, requestId);
        if (existing != null) {
            return toTxn(existing, true);
        }
        requireAccount(appId, accountId);
        long delta = "TOPUP".equals(bizType) ? value : -value;
        move(appId, accountId, delta);
        SandboxTxnDO txn = newTxn(appId, bizType, accountId,
                "TOPUP".equals(bizType) ? "SBX_SYS_FUND" : "SBX_SYS_CASH", null, value, requestId);
        sandboxMapper.insertTxn(txn);
        log.info("沙箱资金单 appId={} kind={} accountId={} amount={} bizNo={} id={}",
                appId, bizType, accountId, value, requestId, txn.getId());
        return toTxn(txn, false);
    }

    /** 沙箱定向划转（借贷两腿都在沙箱账本内，bizNo 幂等）。 */
    @Transactional
    public boolean transfer(String appId, String fromAccountId, String toAccountId, Long amount, String bizNo) {
        requireSandbox(appId);
        long value = amount(amount);
        String requestId = required(bizNo, "bizNo");
        if (sandboxMapper.selectTxnByRequest(appId, requestId) != null) {
            return true;
        }
        requireAccount(appId, fromAccountId);
        requireAccount(appId, toAccountId);
        move(appId, fromAccountId, -value);
        move(appId, toAccountId, value);
        SandboxTxnDO txn = newTxn(appId, "TRANSFER", fromAccountId, toAccountId, null, value, requestId);
        sandboxMapper.insertTxn(txn);
        log.info("沙箱划转 appId={} from={} to={} amount={} bizNo={}", appId, fromAccountId, toAccountId, value, requestId);
        return true;
    }

    @Transactional
    public SandboxOrderDTO createOrder(String appId, String subject, Long amount, String outTradeNo) {
        OpenAppDO app = requireSandbox(appId);
        long value = amount(amount);
        String outNo = outTradeNo == null || outTradeNo.isBlank()
                ? "SBXAUTO" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) : outTradeNo;
        SandboxOrderDO existing = sandboxMapper.selectOrderByOutTradeNo(appId, outNo);
        if (existing != null) {
            return toOrder(existing);
        }
        SandboxOrderDO order = new SandboxOrderDO();
        order.setId("SBXO" + sandboxMapper.nextOrderId());
        order.setAppId(appId);
        order.setMerchantId(app.getMerchantId());
        order.setOutTradeNo(outNo);
        order.setSubject(subject == null || subject.isBlank() ? "沙箱交易" : subject);
        order.setAmount(value);
        order.setStatus("CREATED");
        order.setCreatedAt(System.currentTimeMillis());
        sandboxMapper.insertOrder(order);
        log.info("沙箱下单 appId={} orderId={} outTradeNo={} amount={}", appId, order.getId(), outNo, value);
        return toOrder(order);
    }

    /** 沙箱支付：仅 BALANCE 同步分支（与 v1 trade.pay BALANCE 语义一致），orderId/bizNo 幂等。 */
    @Transactional
    public SandboxTxnDTO pay(String appId, String orderId, String accountId, String method, String bizNo) {
        OpenAppDO app = requireSandbox(appId);
        String payMethod = method == null || method.isBlank() ? "BALANCE" : method.toUpperCase();
        if (!"BALANCE".equals(payMethod)) {
            throw new BizException(ResultCode.PARAM_ERROR, "沙箱仅支持 BALANCE 支付方式");
        }
        SandboxOrderDO order = sandboxMapper.selectOrder(orderId, appId);
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND, "沙箱订单不存在: " + orderId);
        }
        if (order.getPaymentId() != null) {
            SandboxTxnDO paid = sandboxMapper.selectTxn(order.getPaymentId(), appId);
            if (paid != null) {
                return toTxn(paid, true);
            }
        }
        String requestId = bizNo == null || bizNo.isBlank() ? "SBXPAY-" + order.getId() : bizNo;
        SandboxTxnDO existing = sandboxMapper.selectTxnByRequest(appId, requestId);
        if (existing != null) {
            return toTxn(existing, true);
        }
        requireAccount(appId, accountId);
        String merchantAccountId = merchantAccount(app).getAccountId();
        move(appId, accountId, -order.getAmount());
        move(appId, merchantAccountId, order.getAmount());
        SandboxTxnDO txn = newTxn(appId, "PAY", accountId, merchantAccountId, order.getId(), order.getAmount(), requestId);
        sandboxMapper.insertTxn(txn);
        sandboxMapper.markPaid(order.getId(), appId, "SUCCESS", txn.getId(), System.currentTimeMillis());
        log.info("沙箱支付 appId={} orderId={} paymentId={} accountId={} amount={}",
                appId, order.getId(), txn.getId(), accountId, order.getAmount());
        return toTxn(txn, false);
    }

    public SandboxOrderDTO getOrder(String appId, String orderId) {
        requireSandbox(appId);
        SandboxOrderDO order = sandboxMapper.selectOrder(orderId, appId);
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND, "沙箱订单不存在: " + orderId);
        }
        return toOrder(order);
    }

    public SandboxTxnDTO getTxn(String appId, String txnId) {
        requireSandbox(appId);
        SandboxTxnDO txn = sandboxMapper.selectTxn(txnId, appId);
        if (txn == null) {
            throw new BizException(ResultCode.NOT_FOUND, "沙箱支付单不存在: " + txnId);
        }
        return toTxn(txn);
    }

    // ---------- 内部 ----------

    private OpenAppDO requireSandbox(String appId) {
        OpenAppDO app = appId == null ? null : openAppMapper.selectByAppId(appId);
        if (app == null) {
            throw new BizException(ResultCode.NOT_FOUND, "开放平台应用不存在: " + appId);
        }
        if (app.getSandbox() == null || app.getSandbox() != 1) {
            throw new BizException(ResultCode.FORBIDDEN, "非沙箱应用不可访问沙箱账本: " + appId);
        }
        if (!"ACTIVE".equals(app.getStatus())) {
            throw new BizException(ResultCode.FORBIDDEN, "应用授权已撤销: " + appId);
        }
        return app;
    }

    private SandboxAccountDO requireAccount(String appId, String accountId) {
        SandboxAccountDO account = accountId == null ? null : sandboxMapper.selectAccount(accountId);
        if (account == null || !appId.equals(account.getAppId())) {
            throw new BizException(ResultCode.NOT_FOUND, "沙箱账户不存在: " + accountId);
        }
        return account;
    }

    /** 沙箱商户收款账户（每应用一个，懒创建；与生产商户账户无关）。 */
    private SandboxAccountDO merchantAccount(OpenAppDO app) {
        String name = MERCHANT_ACCOUNT_PREFIX + app.getAppId();
        SandboxAccountDO existing = sandboxMapper.selectAccountByName(app.getAppId(), name, "MERCHANT");
        if (existing != null) {
            return existing;
        }
        long seq = sandboxMapper.nextAccountId();
        SandboxAccountDO account = new SandboxAccountDO();
        account.setAccountId("SBXA" + seq);
        account.setMemberId(app.getMerchantId());
        account.setAppId(app.getAppId());
        account.setName(name);
        account.setType("MERCHANT");
        account.setBalance(0L);
        account.setCreatedAt(System.currentTimeMillis());
        sandboxMapper.insertAccount(account);
        return account;
    }

    private void move(String appId, String accountId, long delta) {
        int updated = sandboxMapper.moveBalance(accountId, appId, delta);
        if (updated == 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "沙箱账户余额不足: " + accountId);
        }
    }

    private SandboxTxnDO newTxn(String appId, String bizType, String payer, String payee, String orderId,
                                long amount, String requestId) {
        SandboxTxnDO txn = new SandboxTxnDO();
        txn.setId("SBXP" + sandboxMapper.nextTxnId());
        txn.setAppId(appId);
        txn.setBizType(bizType);
        txn.setMethod("BALANCE");
        txn.setPayerAccountId(payer);
        txn.setToAccountId(payee);
        txn.setOrderId(orderId);
        txn.setAmount(amount);
        txn.setFee(0L);
        txn.setStatus("SUCCESS");
        txn.setRequestId(requestId);
        txn.setCreatedAt(System.currentTimeMillis());
        return txn;
    }

    private static long amount(Long amount) {
        if (amount == null || amount <= 0 || amount > MAX_AMOUNT) {
            throw new BizException(ResultCode.PARAM_ERROR, "amount 必须为正整数分且不超过 1e12");
        }
        return amount;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "缺少参数 " + field);
        }
        return value;
    }

    private SandboxAccountDTO toAccount(SandboxAccountDO source) {
        SandboxAccountDTO dto = new SandboxAccountDTO();
        dto.setAccountId(source.getAccountId());
        dto.setMemberId(source.getMemberId());
        dto.setAppId(source.getAppId());
        dto.setName(source.getName());
        dto.setType(source.getType());
        dto.setBalance(source.getBalance());
        dto.setCreatedAt(source.getCreatedAt());
        return dto;
    }

    private SandboxTxnDTO toTxn(SandboxTxnDO source) {
        return toTxn(source, false);
    }

    private SandboxTxnDTO toTxn(SandboxTxnDO source, boolean repeated) {
        SandboxTxnDTO dto = new SandboxTxnDTO();
        dto.setRepeated(repeated);
        dto.setId(source.getId());
        dto.setAppId(source.getAppId());
        dto.setBizType(source.getBizType());
        dto.setMethod(source.getMethod());
        dto.setPayerAccountId(source.getPayerAccountId());
        dto.setToAccountId(source.getToAccountId());
        dto.setOrderId(source.getOrderId());
        dto.setAmount(source.getAmount());
        dto.setFee(source.getFee());
        dto.setStatus(source.getStatus());
        dto.setRequestId(source.getRequestId());
        dto.setCreatedAt(source.getCreatedAt());
        return dto;
    }

    private SandboxOrderDTO toOrder(SandboxOrderDO source) {
        SandboxOrderDTO dto = new SandboxOrderDTO();
        dto.setId(source.getId());
        dto.setAppId(source.getAppId());
        dto.setMerchantId(source.getMerchantId());
        dto.setOutTradeNo(source.getOutTradeNo());
        dto.setSubject(source.getSubject());
        dto.setAmount(source.getAmount());
        dto.setStatus(source.getStatus());
        dto.setPaymentId(source.getPaymentId());
        dto.setCreatedAt(source.getCreatedAt());
        dto.setPaidAt(source.getPaidAt());
        return dto;
    }
}