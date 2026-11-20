package com.qoder.payment.facade.account;

import com.qoder.payment.common.result.Result;

import java.util.List;
import java.util.Map;

/** 账户域：账户生命周期（开户/冻结），余额权威在账务域。对标支付宝账户系统。 */
public interface AccountFacade {

    Result<AccountDTO> create(String memberId);

    Result<List<AccountDTO>> list(String memberId);

    Result<AccountDTO> get(String accountId);

    Result<AccountDTO> getByMember(String memberId);

    Result<AccountDTO> updateStatus(String accountId, String status);

    /** 账务域记账前批量校验账户存在性与状态。 */
    Result<Map<String, AccountStatusDTO>> check(List<String> accountIds);

    // ---------- P1 additive（A-1/A-2/A-3/M-3；既有方法签名与语义不变） ----------

    /** A-1 类型开户：(memberId,accountType) 唯一幂等，重复返回既有户；缺省 BALANCE。 */
    Result<AccountDTO> createTyped(String memberId, String accountType);

    /** A-3 状态冻结细分：FROZEN 缺省 FULL；JUDICIAL 解冻需 authorizedBy ≠ operator；全程留痕。 */
    Result<AccountDTO> updateStatusEx(StatusChangeRequest request);

    /** M-3 账户等级与限额（DynamicConfig 可配）。 */
    Result<AccountLevelDTO> getLevel(String accountId);

    /** M-3 升级：II 需 KYC 四要素 PASS；III 需 KYC PASS + operator 复核；只升不降，留痕。 */
    Result<AccountDTO> upgradeLevel(String accountId, String targetLevel, String operator);

    /** M-3 限额校验（SINGLE/BALANCE），供 payment/risk 资金路径接线。 */
    Result<LimitCheckDTO> checkLimit(String accountId, long amount, String kind);

    /** A-2 绑卡（四要素沙箱核验 + Luhn + 卡 BIN）；卡号 hash+mask 落库；(accountId,cardNoHash) 幂等。 */
    Result<BankCardDTO> bindCard(BindCardRequest request);

    /** A-2 解绑：status UNBOUND，不删除记录（在途交易卡快照不受影响）；默认卡自动顺位。 */
    Result<BankCardDTO> unbindCard(String cardId, String operator);

    /** A-2 默认卡切换：一账户一默认卡（事务内清旧设新）。 */
    Result<BankCardDTO> setDefaultCard(String accountId, String cardId);

    /** A-2 卡列表（脱敏出参，BOUND 在前）。 */
    Result<List<BankCardDTO>> listCards(String accountId);

    /** A-3 金额冻结（按原因细分，经账务 TCC Try；bizNo 幂等；凭证留痕）。 */
    Result<AccountFreezeDTO> freezeAmount(FreezeRequest request);

    /** A-3 解冻（须对应原因；JUDICIAL 双人复核；经账务 TCC Cancel；bizNo 幂等）。 */
    Result<AccountFreezeDTO> unfreezeAmount(UnfreezeRequest request);

    /** A-3 冻结单查询（status 可选 ACTIVE/RELEASED）。 */
    Result<List<AccountFreezeDTO>> listFreezes(String accountId, String status);

    // ---------- P2 additive（A-4 多币种；既有方法签名/语义/幂等键一字未改） ----------

    /**
     * A-4 多币种开户：(memberId,accountType,currency) 唯一幂等，重复返回既有户。
     * currency 为空 → CNY（与 {@link #createTyped(String, String)} 逐字节等价）；
     * 既有 CNY 户不受影响，account.open 懒开户仍取 CNY BALANCE 户。
     */
    Result<AccountDTO> createCurrency(String memberId, String accountType, String currency);

    // ---------- P2 additive（A-5 开销户/升降级；既有方法签名/语义/幂等键一字未改） ----------

    /**
     * A-5 销户（逻辑销户，<b>不物理删除账户行</b>）：(ACCOUNT_CLOSE,requestId) 幂等。
     *
     * <p>状态机 ACTIVE→CLOSING→CLOSED（中途失败回 ACTIVE 并留 CLOSE_ABORT 痕）。
     * <p>护栏（任一不满足即拒绝，绝不动资金）：账务余额=0、无 ACTIVE 金额冻结单、
     * 状态 ACTIVE 且 freezeType=NONE、无在途支付/提现、无未释放保证金/未回收垫资。
     * <p>CLOSED 后 {@code status=CLOSED} ⇒ 账务域记账前置校验天然拒绝，账户不可用。
     * <p>ride 既有账户不受影响：本方法只对被显式请求的账户生效，懒开户/余额口径一字未改。
     */
    Result<AccountDTO> closeAccount(AccountCloseRequest request);

    /**
     * A-5 等级升降级（M-3 限额联动 + KYC 校验 + 留痕）。
     * 升级沿用 M-3 既有校验；降级需 operator 复核且余额不得超目标等级余额上限。
     * 目标等级=当前等级时幂等返回（不重复留痕）。
     */
    Result<AccountDTO> changeLevel(LevelChangeRequest request);

    /** A-5 生命周期留痕查询（只读，按时间正序）。 */
    Result<List<AccountLifecycleLogDTO>> listLifecycleLogs(String accountId);
}