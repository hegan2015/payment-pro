package com.qoder.payment.account.level;

import com.qoder.payment.account.dataobject.AccountDO;
import com.qoder.payment.account.dataobject.AccountLevelRuleDO;
import com.qoder.payment.account.mapper.AccountLevelRuleMapper;
import com.qoder.payment.account.mapper.AccountMapper;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountLevelDTO;
import com.qoder.payment.facade.account.LimitCheckDTO;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.member.KycResultDTO;
import com.qoder.payment.facade.member.MemberFacade;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * M-3 账户分级限额（I/II/III 类户）：
 * <ul>
 *   <li>规则表 account_level_rule 幂等种子（仅缺失插入，不覆盖既有配置）；
 *       DynamicConfig {@code account.level.<L>.single-limit/daily-limit/balance-limit} 热覆盖；</li>
 *   <li>默认限额宽松（I 类单笔 10 万元），且资金路径执行点由 payment/risk 接线 checkLimit——
 *       本期不拦截任何既有 ride/smoke 流量（红线 §0.7）；</li>
 *   <li>升级：II 需 KYC 四要素 PASS；III 需 KYC PASS + operator 复核；只升不降；
 *       等级变更经网关 AuditFilter 全量审计 + 日志留痕。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountLevelService {

    private static final List<String> LEVELS = List.of("I", "II", "III");
    /** 代码默认值（种子行缺失时兜底；单位分）。 */
    private static final Map<String, long[]> DEFAULTS = Map.of(
            "I", new long[]{10_000_000L, 50_000_000L, 100_000_000L},
            "II", new long[]{50_000_000L, 200_000_000L, 500_000_000L},
            "III", new long[]{1_000_000_000L, 5_000_000_000L, 10_000_000_000L});
    private static final Map<String, String> REQUIRE_KYC = Map.of(
            "I", "NONE", "II", "FOUR_ELEMENT", "III", "STRONG");

    private final AccountLevelRuleMapper ruleMapper;
    private final AccountMapper accountMapper;
    private final DynamicConfig dynamicConfig;

    @DubboReference
    private MemberFacade memberFacade;

    @DubboReference
    private AccountingFacade accountingFacade;

    @PostConstruct
    public void seed() {
        for (String level : LEVELS) {
            if (ruleMapper.selectByLevel(level) == null) {
                AccountLevelRuleDO rule = new AccountLevelRuleDO();
                rule.setLevel(level);
                long[] def = DEFAULTS.get(level);
                rule.setSingleLimit(def[0]);
                rule.setDailyLimit(def[1]);
                rule.setBalanceLimit(def[2]);
                rule.setRequireKyc(REQUIRE_KYC.get(level));
                try {
                    ruleMapper.insert(rule);
                    log.info("账户分级限额种子 level={} single={} daily={} balance={}", level, def[0], def[1], def[2]);
                } catch (DuplicateKeyException ignored) {
                    // 多实例并发种子：对手已插入
                }
            }
        }
    }

    public AccountLevelDTO getLevel(String accountId) {
        AccountDO account = require(accountId);
        String level = effectiveLevel(account);
        AccountLevelRuleDO rule = rule(level);
        AccountLevelDTO dto = new AccountLevelDTO();
        dto.setAccountId(accountId);
        dto.setLevel(level);
        dto.setSingleLimit(dynamicConfig.getLong("account.level." + level + ".single-limit", rule.getSingleLimit()));
        dto.setDailyLimit(dynamicConfig.getLong("account.level." + level + ".daily-limit", rule.getDailyLimit()));
        dto.setBalanceLimit(dynamicConfig.getLong("account.level." + level + ".balance-limit", rule.getBalanceLimit()));
        dto.setRequireKyc(rule.getRequireKyc());
        dto.setKycLevel(kycLevel(account.getMemberId()));
        return dto;
    }

    public AccountDTO upgradeLevel(String accountId, String targetLevel, String operator) {
        AccountDO account = require(accountId);
        String target = targetLevel == null ? "" : targetLevel.trim().toUpperCase();
        if (!LEVELS.contains(target)) {
            throw new BizException(ResultCode.PARAM_ERROR, "targetLevel 仅支持 I/II/III");
        }
        String current = effectiveLevel(account);
        if (LEVELS.indexOf(target) <= LEVELS.indexOf(current)) {
            throw new BizException(ResultCode.CONFLICT, "只升不降: 当前=" + current + " 目标=" + target);
        }
        String kycLevel = kycLevel(account.getMemberId());
        if (!"II".equals(kycLevel) && !"III".equals(kycLevel)) {
            throw new BizException(ResultCode.BIZ_ERROR, "升级需 KYC 四要素 PASS（M-2），当前未实名");
        }
        if ("III".equals(target) && (operator == null || operator.isBlank())) {
            throw new BizException(ResultCode.FORBIDDEN, "III 类户升级需 operator 复核（强 KYC）");
        }
        accountMapper.updateLevel(accountId, target);
        log.info("账户等级升级 accountId={} {}→{} operator={} kycLevel={}（网关审计留痕）",
                accountId, current, target, operator, kycLevel);
        return com.qoder.payment.account.convert.AccountConverter.toDTO(
                accountMapper.selectById(accountId), accountingFacade.balance(accountId).orThrow());
    }

    /**
     * A-5 additive：取某等级的限额规则（不依赖具体账户，供降级前置校验）。
     * 与 {@link #getLevel(String)} 同一口径（种子行 + DynamicConfig 热覆盖），只读不落库。
     */
    public AccountLevelDTO levelRule(String level) {
        String key = level == null ? "" : level.trim().toUpperCase();
        if (!LEVELS.contains(key)) {
            throw new BizException(ResultCode.PARAM_ERROR, "level 仅支持 I/II/III");
        }
        AccountLevelRuleDO rule = rule(key);
        AccountLevelDTO dto = new AccountLevelDTO();
        dto.setLevel(key);
        dto.setSingleLimit(dynamicConfig.getLong("account.level." + key + ".single-limit", rule.getSingleLimit()));
        dto.setDailyLimit(dynamicConfig.getLong("account.level." + key + ".daily-limit", rule.getDailyLimit()));
        dto.setBalanceLimit(dynamicConfig.getLong("account.level." + key + ".balance-limit", rule.getBalanceLimit()));
        dto.setRequireKyc(rule.getRequireKyc());
        return dto;
    }

    /** 限额校验（SINGLE/BALANCE）：供 payment/risk 资金路径接线；日累计由 R-2 多维限额执行。 */
    public LimitCheckDTO checkLimit(String accountId, long amount, String kind) {
        AccountLevelDTO level = getLevel(accountId);
        LimitCheckDTO dto = new LimitCheckDTO();
        dto.setLevel(level.getLevel());
        dto.setKind(kind);
        dto.setAmount(amount);
        if ("SINGLE".equalsIgnoreCase(kind)) {
            dto.setLimit(level.getSingleLimit());
            dto.setPass(amount <= level.getSingleLimit());
            dto.setReason(Boolean.TRUE.equals(dto.getPass()) ? null
                    : "超单笔限额: " + amount + " > " + level.getSingleLimit());
        } else if ("BALANCE".equalsIgnoreCase(kind)) {
            long balance = accountingFacade.balance(accountId).orThrow();
            dto.setLimit(level.getBalanceLimit());
            dto.setPass(balance + amount <= level.getBalanceLimit());
            dto.setReason(Boolean.TRUE.equals(dto.getPass()) ? null
                    : "超余额上限: " + balance + "+" + amount + " > " + level.getBalanceLimit());
        } else {
            throw new BizException(ResultCode.PARAM_ERROR, "kind 仅支持 SINGLE/BALANCE");
        }
        return dto;
    }

    // ------------------------------------------------------------------ internal

    private AccountDO require(String accountId) {
        AccountDO account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new BizException(ResultCode.NOT_FOUND, "账户不存在: " + accountId);
        }
        return account;
    }

    private static String effectiveLevel(AccountDO account) {
        return account.getLevel() == null || account.getLevel().isBlank() ? "I" : account.getLevel();
    }

    private AccountLevelRuleDO rule(String level) {
        AccountLevelRuleDO rule = ruleMapper.selectByLevel(level);
        if (rule == null) {
            rule = new AccountLevelRuleDO();
            rule.setLevel(level);
            long[] def = DEFAULTS.get(level);
            rule.setSingleLimit(def[0]);
            rule.setDailyLimit(def[1]);
            rule.setBalanceLimit(def[2]);
            rule.setRequireKyc(REQUIRE_KYC.get(level));
        }
        return rule;
    }

    private String kycLevel(String memberId) {
        Result<KycResultDTO> kyc = memberFacade.getKyc(memberId);
        return kyc.isSuccess() && kyc.getData() != null && Boolean.TRUE.equals(kyc.getData().getPass())
                ? kyc.getData().getLevel() : null;
    }
}