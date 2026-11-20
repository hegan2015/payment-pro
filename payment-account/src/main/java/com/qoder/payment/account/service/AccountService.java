package com.qoder.payment.account.service;

import com.qoder.payment.account.convert.AccountConverter;
import com.qoder.payment.account.dataobject.AccountDO;
import com.qoder.payment.account.freeze.FreezeLogService;
import com.qoder.payment.account.mapper.AccountMapper;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountStatusDTO;
import com.qoder.payment.facade.account.AccountType;
import com.qoder.payment.facade.account.FreezeType;
import com.qoder.payment.facade.account.StatusChangeRequest;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.member.KycResultDTO;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.member.MemberFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@org.springframework.context.annotation.DependsOn("accountSchemaUpgrade")
public class AccountService {

    /** M-2 开户 KYC 闸门开关：默认 false（保 ride account.open 懒开户红线 §0.7）。 */
    public static final String KYC_REQUIRED_FOR_OPEN = "account.kyc.required-for-open";
    /** A-4 启用币种白名单（DynamicConfig 可热更）；既有账户恒 CNY，默认路径逐字节不变。 */
    public static final String SUPPORTED_CURRENCIES = "account.currencies";
    /** A-4 缺省币种（本位币）。 */
    public static final String DEFAULT_CURRENCY = "CNY";

    private final AccountMapper accountMapper;
    private final DynamicConfig dynamicConfig;
    private final FreezeLogService freezeLogService;
    /** A-5 additive：开户留痕（只追加；best-effort，不阻断开户）。 */
    private final com.qoder.payment.account.mapper.AccountLifecycleMapper lifecycleMapper;

    @DubboReference
    private MemberFacade memberFacade;

    @DubboReference
    private AccountingFacade accountingFacade;

    /** 既有开户语义不变：缺省 BALANCE 户，重复开户 409（ride account.open 幂等由网关懒开户兜底）。 */
    public AccountDTO create(String memberId) {
        return createInternal(memberId, AccountType.BALANCE, false);
    }

    /** A-1 类型开户：(memberId,accountType) 唯一幂等，重复返回既有户。 */
    public AccountDTO createTyped(String memberId, String accountType) {
        AccountType type;
        try {
            type = AccountType.of(accountType);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_ERROR, "accountType 仅支持 BALANCE/DEPOSIT/SUB/MARKETING/PENDING_SETTLE");
        }
        return createInternal(memberId, type, true);
    }

    /**
     * A-4 多币种开户：(memberId,accountType,currency) 唯一幂等。
     * currency 空 → CNY，与 {@link #createTyped(String, String)} 逐字节等价（既有账户默认 CNY）。
     */
    public AccountDTO createCurrency(String memberId, String accountType, String currency) {
        AccountType type;
        try {
            type = AccountType.of(accountType);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_ERROR, "accountType 仅支持 BALANCE/DEPOSIT/SUB/MARKETING/PENDING_SETTLE");
        }
        return createInternal(memberId, type, true, normalizeCurrency(currency));
    }

    /** A-4 币种归一 + 白名单校验（DynamicConfig {@code account.currencies} 可配，默认 CNY,USD）。 */
    private String normalizeCurrency(String currency) {
        String ccy = currency == null || currency.isBlank() ? DEFAULT_CURRENCY : currency.trim().toUpperCase(Locale.ROOT);
        if (!ccy.matches("[A-Z]{3}")) {
            throw new BizException(ResultCode.PARAM_ERROR, "currency 需为 ISO 4217 三字母代码（如 CNY/USD）");
        }
        String supported = dynamicConfig.get(SUPPORTED_CURRENCIES, "CNY,USD");
        boolean allowed = false;
        for (String part : supported.split(",")) {
            if (ccy.equals(part.trim().toUpperCase(Locale.ROOT))) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            throw new BizException(ResultCode.PARAM_ERROR, "币种未启用: " + ccy + "（account.currencies=" + supported + "）");
        }
        return ccy;
    }

    private AccountDTO createInternal(String memberId, AccountType type, boolean idempotent) {
        return createInternal(memberId, type, idempotent, DEFAULT_CURRENCY);
    }

    private AccountDTO createInternal(String memberId, AccountType type, boolean idempotent, String currency) {
        MemberDTO member = memberFacade.get(memberId).orThrow();
        // M-2：KYC 闸门可配（默认关闭）；开启后 USER 开户需四要素 PASS，MERCHANT 走进件审核（M-4）
        if (dynamicConfig.getBool(KYC_REQUIRED_FOR_OPEN, false) && "USER".equals(member.getType())) {
            Result<KycResultDTO> kyc = memberFacade.getKyc(memberId);
            if (!kyc.isSuccess() || kyc.getData() == null || !Boolean.TRUE.equals(kyc.getData().getPass())) {
                throw new BizException(ResultCode.BIZ_ERROR, "KYC 未通过，禁止开户（account.kyc.required-for-open=true）");
            }
        }
        AccountDO existing = accountMapper.selectByMemberTypeCurrency(memberId, type.name(), currency);
        if (existing != null) {
            if (idempotent) {
                return get(existing.getId());
            }
            throw new BizException(ResultCode.CONFLICT, "该会员已开户: " + existing.getId());
        }
        AccountDO account = new AccountDO();
        account.setId("A" + accountMapper.nextId());
        account.setMemberId(memberId);
        account.setMemberName(member.getName());
        account.setType(member.getType());
        account.setStatus("ACTIVE");
        account.setCreatedAt(System.currentTimeMillis());
        account.setAccountType(type.name());
        account.setLevel("I");
        account.setFreezeType("NONE");
        account.setCurrency(currency);
        try {
            accountMapper.insert(account);
        } catch (DuplicateKeyException e) {
            AccountDO winner = accountMapper.selectByMemberTypeCurrency(memberId, type.name(), currency);
            if (idempotent && winner != null) {
                return get(winner.getId());
            }
            throw new BizException(ResultCode.CONFLICT, "该会员已开户: " + (winner == null ? memberId : winner.getId()));
        }
        log.info("账户开立 accountId={} memberId={} type={} accountType={} currency={}", account.getId(), memberId,
                account.getType(), type, currency);
        // A-5 additive：开户留痕（best-effort，绝不影响开户幂等语义与 ride 懒开户红线）
        try {
            com.qoder.payment.account.dataobject.AccountLifecycleLogDO row =
                    new com.qoder.payment.account.dataobject.AccountLifecycleLogDO();
            row.setId("AL" + lifecycleMapper.nextId());
            row.setAccountId(account.getId());
            row.setAction("OPEN");
            row.setToStatus("ACTIVE");
            row.setToLevel("I");
            row.setOperator("open-api");
            row.setReason("开户 " + type.name() + "/" + currency);
            row.setCreatedAt(System.currentTimeMillis());
            lifecycleMapper.insert(row);
        } catch (Exception e) {
            log.warn("A-5 开户留痕失败（不影响开户）accountId={}: {}", account.getId(), e.toString());
        }
        return AccountConverter.toDTO(account, 0L);
    }

    public List<AccountDTO> list(String memberId) {
        List<AccountDO> accounts = accountMapper.selectAll();
        if (memberId != null && !memberId.isBlank()) {
            accounts = accounts.stream().filter(a -> memberId.equals(a.getMemberId())).toList();
            // A-1：BALANCE 户恒排首位——account.open 懒开户取 list.get(0)，多类型户下语义不回归
            // A-4：同类型内 CNY 户恒排首位（存量全 CNY ⇒ 比较键恒等，排序与改造前逐字节一致）
            accounts = accounts.stream()
                    .sorted(Comparator.comparing((AccountDO a) -> !"BALANCE".equals(effectiveType(a)))
                            .thenComparing(a -> !DEFAULT_CURRENCY.equals(effectiveCurrency(a)))
                            .thenComparing(AccountDO::getCreatedAt).thenComparing(AccountDO::getId))
                    .toList();
        }
        if (accounts.isEmpty()) {
            return List.of();
        }
        // 锁外聚合账务域余额，避免跨域持锁等待
        List<String> ids = accounts.stream().map(AccountDO::getId).toList();
        Map<String, Long> balances = accountingFacade.balances(ids).orThrow();
        List<AccountDTO> result = new ArrayList<>();
        for (AccountDO account : accounts) {
            result.add(AccountConverter.toDTO(account, balances.getOrDefault(account.getId(), 0L)));
        }
        return result;
    }

    public AccountDTO get(String accountId) {
        AccountDO account = require(accountId);
        long balance = accountingFacade.balance(accountId).orThrow();
        return AccountConverter.toDTO(account, balance);
    }

    /** 缺省返回 BALANCE 户（A-1 兼容红线：既有单户语义不变，余额口径同改造前返回 0）。 */
    public AccountDTO getByMember(String memberId) {
        AccountDO account = accountMapper.selectListByMemberId(memberId).stream()
                .filter(a -> "BALANCE".equals(effectiveType(a)))
                // A-4：缺省币种户（CNY）——存量全 CNY，过滤恒真，语义不变
                .filter(a -> DEFAULT_CURRENCY.equals(effectiveCurrency(a)))
                .findFirst().orElse(null);
        if (account == null) {
            throw new BizException(ResultCode.NOT_FOUND, "会员未开户: " + memberId);
        }
        return AccountConverter.toDTO(account, 0L);
    }

    /** 既有两参语义不变：FROZEN→FULL；JUDICIAL 户解冻必须走 updateStatusEx 双人复核。 */
    public AccountDTO updateStatus(String accountId, String status) {
        StatusChangeRequest request = new StatusChangeRequest();
        request.setAccountId(accountId);
        request.setStatus(status);
        request.setFreezeType("FROZEN".equals(status) ? "FULL" : "NONE");
        request.setOperator("legacy-api");
        return updateStatusEx(request);
    }

    /** A-3 状态冻结细分：FULL/JUDICIAL（非 ACTIVE 账务全方向拒绝）；JUDICIAL 解冻需 authorizedBy≠operator；全程留痕。 */
    public AccountDTO updateStatusEx(StatusChangeRequest request) {
        String status = request.getStatus();
        if (!"ACTIVE".equals(status) && !"FROZEN".equals(status)) {
            throw new BizException(ResultCode.PARAM_ERROR, "status 仅支持 ACTIVE 或 FROZEN");
        }
        AccountDO account = require(request.getAccountId());
        // A-5 additive 护栏：CLOSING/CLOSED 账户禁止状态变更（防已销户账户被「解冻」复活）。
        // 既有账户 lifecycle_status 恒 ACTIVE ⇒ 本护栏对存量流量恒真，行为逐字节不变。
        String lifecycle = AccountLifecycleService.lifecycleOf(account);
        if (!AccountLifecycleService.ACTIVE.equals(lifecycle)) {
            throw new BizException(ResultCode.CONFLICT,
                    "账户生命周期非 ACTIVE（" + lifecycle + "），不可变更状态: " + account.getId());
        }
        FreezeType freezeType;
        try {
            freezeType = FreezeType.of(request.getFreezeType());
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "freezeType 仅支持 NONE/FULL/JUDICIAL（方向性冻结需账务域接线，见 FreezeType  javadoc）");
        }
        if ("FROZEN".equals(status) && freezeType == FreezeType.NONE) {
            freezeType = FreezeType.FULL;
        }
        if ("ACTIVE".equals(status)) {
            if (FreezeType.JUDICIAL.name().equals(account.getFreezeType())) {
                String authorizedBy = request.getAuthorizedBy();
                String operator = request.getOperator();
                if (authorizedBy == null || authorizedBy.isBlank() || authorizedBy.equals(operator)) {
                    throw new BizException(ResultCode.FORBIDDEN, "司法冻结解冻需双人复核（authorizedBy ≠ operator）");
                }
            }
            freezeType = FreezeType.NONE;
        }
        AccountDO update = new AccountDO();
        update.setId(account.getId());
        update.setStatus(status);
        update.setFreezeType(freezeType.name());
        update.setFreezeReason("FROZEN".equals(status) ? request.getReason() : null);
        update.setFreezeAt("FROZEN".equals(status) ? System.currentTimeMillis() : null);
        update.setExpectedStatus(account.getStatus());
        if (accountMapper.updateStatusEx(update) == 0) {
            throw new BizException(ResultCode.CONFLICT, "账户状态已变更（并发 CAS 拒绝）: " + account.getId());
        }
        freezeLogService.log(account.getId(), "FROZEN".equals(status) ? "STATUS_FREEZE" : "STATUS_UNFREEZE",
                freezeType.name(), null, request.getReason(), null,
                request.getOperator(), request.getAuthorizedBy(), null);
        log.info("账户状态变更 accountId={} status={} freezeType={} operator={}", account.getId(), status,
                freezeType, request.getOperator());
        return get(account.getId());
    }

    public Map<String, AccountStatusDTO> check(List<String> accountIds) {
        Map<String, AccountStatusDTO> result = new LinkedHashMap<>();
        for (String id : accountIds) {
            AccountDO account = require(id);
            AccountStatusDTO status = new AccountStatusDTO();
            status.setStatus(account.getStatus());
            status.setType(account.getType());
            status.setAccountType(effectiveType(account));
            status.setFreezeType(account.getFreezeType() == null ? "NONE" : account.getFreezeType());
            status.setCurrency(effectiveCurrency(account));
            result.put(id, status);
        }
        return result;
    }

    AccountDO require(String accountId) {
        AccountDO account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new BizException(ResultCode.NOT_FOUND, "账户不存在: " + accountId);
        }
        return account;
    }

    static String effectiveType(AccountDO account) {
        return account.getAccountType() == null || account.getAccountType().isBlank()
                ? "BALANCE" : account.getAccountType();
    }

    /** A-4 存量行/缺省币种归一：NULL/空白 → CNY（既有账户行为逐字节不变）。 */
    static String effectiveCurrency(AccountDO account) {
        return account.getCurrency() == null || account.getCurrency().isBlank()
                ? DEFAULT_CURRENCY : account.getCurrency().trim().toUpperCase(Locale.ROOT);
    }
}