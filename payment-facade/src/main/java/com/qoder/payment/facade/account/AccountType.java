package com.qoder.payment.facade.account;

/**
 * A-1 账户类型：一会员一类型一户，(memberId, accountType) 唯一幂等。
 * 各类型独立 accountId + 独立复式核算；记账口径不变（balance=credit-debit, available=balance-frozen）。
 * 缺省 BALANCE 兼容现状（getByMember / account.open 懒开户均返回 BALANCE 户）。
 */
public enum AccountType {

    /** 余额户（缺省，兼容既有唯一户语义）。 */
    BALANCE,
    /** 保证金户（ST-6 冻结/释放联动）。 */
    DEPOSIT,
    /** 子账户（业务分账核算）。 */
    SUB,
    /** 营销户（AC-2 营销资金）。 */
    MARKETING,
    /** 待清算户（ST-1 T+N 结算前资金挂账）。 */
    PENDING_SETTLE;

    /** 解析类型；null/空白 → BALANCE（兼容缺省）；非法值抛 IllegalArgumentException。 */
    public static AccountType of(String value) {
        if (value == null || value.isBlank()) {
            return BALANCE;
        }
        return valueOf(value.trim().toUpperCase());
    }
}