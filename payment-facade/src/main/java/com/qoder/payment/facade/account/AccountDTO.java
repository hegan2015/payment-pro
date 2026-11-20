package com.qoder.payment.facade.account;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AccountDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String memberId;
    private String memberName;
    private String type;
    private String status;
    private Long balance;
    private Long createdAt;

    // ---------- P1 additive（A-1/A-3/M-3；既有字段与语义不变） ----------

    /** A-1 账户类型：BALANCE/DEPOSIT/SUB/MARKETING/PENDING_SETTLE（既有户缺省 BALANCE）。 */
    private String accountType;
    /** M-3 账户等级：I/II/III（既有户缺省 I）。 */
    private String level;
    /** A-3 状态冻结类型：NONE/FULL/JUDICIAL。 */
    private String freezeType;
    /** A-3 冻结原因说明。 */
    private String freezeReason;
    /** A-3 状态冻结时间（毫秒）。 */
    private Long freezeAt;

    // ---------- P2 C 泳道 additive（A-4 多币种；既有字段与语义不变） ----------

    /**
     * A-4 账户币种（ISO 4217，每账户单币种）。既有账户缺省 CNY，行为逐字节不变；
     * 多币种会员经 {@code createCurrency} 开立外币户（uk=member_id+account_type+currency）。
     */
    private String currency;

    // ---------- P2 G 泳道 additive（A-5 生命周期；既有字段与语义不变） ----------

    /**
     * A-5 生命周期状态：ACTIVE（正常）/ CLOSING（销户中）/ CLOSED（已销户）。
     * 既有账户缺省 ACTIVE，行为逐字节不变；<b>销户是逻辑状态，账户行不物理删除</b>。
     * CLOSED 时 {@code status} 同步置 CLOSED ⇒ 账务域 checkAccounts 天然拒绝任何记账。
     */
    private String lifecycleStatus;
    /** A-5 销户时间（毫秒，未销户为 null）。 */
    private Long closedAt;
}