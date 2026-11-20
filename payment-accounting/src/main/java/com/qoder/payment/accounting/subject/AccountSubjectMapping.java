package com.qoder.payment.accounting.subject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AC-2 内置科目目录 + 既有系统户固定映射对照表（<b>唯一权威口径</b>）。
 *
 * <p><b>资金红线</b>：科目只是分录的分类标签。系统户余额仍由 {@code account_balance} 权威计算
 * （balance = 贷方累计 − 借方累计），科目映射<b>不改变任何记账方向、金额、幂等键与余额口径</b>：
 * <ul>
 *   <li>SYS_CASH → 1001 备付金现金（资产，借增贷减）——充值 D SYS_CASH / 提现 C SYS_CASH 逐字不变；</li>
 *   <li>SYS_FEE → 6001 手续费收入（损益，贷增借减）——feeBps→SYS_FEE 口径不变；</li>
 *   <li>SYS_FUND → 2005 待清算入金（负债）——ride fund.topup/withdraw 资金单路径不变；</li>
 *   <li>SYS_SUSPENSE → 2003 差错挂账（负债）——S-6 挂账/蓝补语义不变；</li>
 *   <li>SYS_PAYOUT → 2004 在途提现款（负债）——ST-4 提现受理/到账/回滚语义不变；</li>
 *   <li>SYS_CLEARING → 3001 清算资金往来（共同类）；</li>
 *   <li>SYS_PROFIT → 4001 损益结转（权益，AC-3 新增系统户，只在显式开启结转时动账）。</li>
 * </ul>
 *
 * <p>客户账户（非 SYS_）默认 2001 客户余额（负债），可经 mapAccountSubject 显式改挂其它明细科目。
 */
public final class AccountSubjectMapping {

    /** 类别根（level=1，只汇总不记账）。 */
    public static final String ROOT_ASSET = "1000";
    public static final String ROOT_LIABILITY = "2000";
    public static final String ROOT_COMMON = "3000";
    public static final String ROOT_EQUITY = "4000";
    public static final String ROOT_INCOME_EXPENSE = "6000";

    /** 客户账户默认科目。 */
    public static final String CUSTOMER_BALANCE = "2001";
    /** 未登记系统户兜底科目（保证任何账户都有科目，试算按科目仍平衡）。 */
    public static final String UNMAPPED = "2999";
    /** P2 A-4 本位币（缺省币种账套）：既有系统户裸名即 CNY 账套，逐字节不变。 */
    public static final String DEFAULT_CURRENCY = "CNY";

    public static final String TYPE_ASSET = "ASSET";
    public static final String TYPE_LIABILITY = "LIABILITY";
    public static final String TYPE_COMMON = "COMMON";
    public static final String TYPE_EQUITY = "EQUITY";
    public static final String TYPE_INCOME = "INCOME";
    public static final String TYPE_EXPENSE = "EXPENSE";

    /** 科目定义（不可变值对象）。 */
    public record Def(String code, String name, String type, String direction, String parent,
                      int level, String systemAccount) {
    }

    private static final List<Def> CATALOG = List.of(
            new Def(ROOT_ASSET, "资产类", TYPE_ASSET, "D", null, 1, null),
            new Def(ROOT_LIABILITY, "负债类", TYPE_LIABILITY, "C", null, 1, null),
            new Def(ROOT_COMMON, "共同类", TYPE_COMMON, "D", null, 1, null),
            new Def(ROOT_EQUITY, "权益类", TYPE_EQUITY, "C", null, 1, null),
            new Def(ROOT_INCOME_EXPENSE, "损益类", TYPE_INCOME, "C", null, 1, null),
            new Def("1001", "备付金现金", TYPE_ASSET, "D", ROOT_ASSET, 2, "SYS_CASH"),
            new Def(CUSTOMER_BALANCE, "客户余额", TYPE_LIABILITY, "C", ROOT_LIABILITY, 2, null),
            new Def("2002", "担保资金", TYPE_LIABILITY, "C", ROOT_LIABILITY, 2, "SYS_ESCROW"),
            new Def("2003", "差错挂账", TYPE_LIABILITY, "C", ROOT_LIABILITY, 2, "SYS_SUSPENSE"),
            new Def("2004", "在途提现款", TYPE_LIABILITY, "C", ROOT_LIABILITY, 2, "SYS_PAYOUT"),
            new Def("2005", "待清算入金", TYPE_LIABILITY, "C", ROOT_LIABILITY, 2, "SYS_FUND"),
            new Def("2006", "保证金", TYPE_LIABILITY, "C", ROOT_LIABILITY, 2, "SYS_DEPOSIT"),
            new Def(UNMAPPED, "其他待映射", TYPE_LIABILITY, "C", ROOT_LIABILITY, 2, null),
            new Def("3001", "清算资金往来", TYPE_COMMON, "D", ROOT_COMMON, 2, "SYS_CLEARING"),
            new Def("4001", "损益结转", TYPE_EQUITY, "C", ROOT_EQUITY, 2, "SYS_PROFIT"),
            new Def("6001", "手续费收入", TYPE_INCOME, "C", ROOT_INCOME_EXPENSE, 2, "SYS_FEE"),
            new Def("6002", "营销支出", TYPE_EXPENSE, "D", ROOT_INCOME_EXPENSE, 2, "SYS_MARKETING"),
            new Def("6601", "汇兑损益", TYPE_EXPENSE, "D", ROOT_INCOME_EXPENSE, 2, "SYS_FX"));

    private static final Map<String, Def> BY_CODE = new LinkedHashMap<>();
    private static final Map<String, Def> BY_SYSTEM_ACCOUNT = new LinkedHashMap<>();

    static {
        for (Def def : CATALOG) {
            BY_CODE.put(def.code(), def);
            if (def.systemAccount() != null) {
                BY_SYSTEM_ACCOUNT.put(def.systemAccount(), def);
            }
        }
    }

    public static List<Def> catalog() {
        return CATALOG;
    }

    public static Def byCode(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /** 系统户固定映射（不可改）；未登记系统户返回 null，由服务层落 {@link #UNMAPPED}。 */
    public static Def bySystemAccount(String accountId) {
        if (accountId == null) {
            return null;
        }
        Def exact = BY_SYSTEM_ACCOUNT.get(accountId);
        if (exact != null) {
            return exact;
        }
        // P2 A-4 additive：外币系统户 SYS_<NAME>_<CCY>（如 SYS_CASH_USD / SYS_FX_USD）
        // 沿用本位币同名科目（1001/6601/6003…）——科目是分类标签，币种由账套列区分。
        // 精确匹配优先 ⇒ 既有系统户解析逐字节不变。
        String base = baseSystemAccount(accountId);
        return base == null ? null : BY_SYSTEM_ACCOUNT.get(base);
    }

    /**
     * A-4 外币系统户命名约定：{@code SYS_CASH_USD} → {@code SYS_CASH}；本位币（CNY）用裸名 {@code SYS_CASH}。
     * 仅当去掉尾缀后仍是<b>已登记</b>系统户、且尾缀是三位大写字母（ISO 4217）时才剥离，
     * 否则返回 null（不误伤任何既有账户 id）。
     */
    public static String baseSystemAccount(String accountId) {
        if (accountId == null || !accountId.startsWith("SYS_")) {
            return null;
        }
        // 已登记的完整系统户名优先（如 SYS_FX_FEE 是 6003 汇兑手续费户，绝不可误拆成 SYS_FX + 币种 FEE）
        if (BY_SYSTEM_ACCOUNT.containsKey(accountId)) {
            return null;
        }
        int idx = accountId.lastIndexOf('_');
        if (idx <= 3 || idx == accountId.length() - 1) {
            return null;
        }
        String suffix = accountId.substring(idx + 1);
        if (!suffix.matches("[A-Z]{3}") || DEFAULT_CURRENCY.equals(suffix)) {
            return null;
        }
        String base = accountId.substring(0, idx);
        return BY_SYSTEM_ACCOUNT.containsKey(base) ? base : null;
    }

    /** A-4 系统户所属币种账套：外币尾缀 → 该币种；其余（含全部既有系统户）→ CNY。 */
    public static String currencyOfSystemAccount(String accountId) {
        String base = baseSystemAccount(accountId);
        return base == null ? DEFAULT_CURRENCY : accountId.substring(base.length() + 1);
    }

    /** A-4 按币种取系统户 id：CNY → 裸名（既有 id 逐字节不变）；外币 → {@code base_CCY}。 */
    public static String systemAccountFor(String base, String currency) {
        if (currency == null || currency.isBlank() || DEFAULT_CURRENCY.equals(currency)) {
            return base;
        }
        return base + "_" + currency;
    }

    public static boolean isSystem(String accountId) {
        return accountId != null && accountId.startsWith("SYS_");
    }

    /** 损益类科目（AC-3 结转对象）。 */
    public static boolean isProfitLoss(String type) {
        return TYPE_INCOME.equals(type) || TYPE_EXPENSE.equals(type);
    }

    private AccountSubjectMapping() {
    }
}