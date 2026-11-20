package com.qoder.payment.account.dataobject;

import lombok.Data;

@Data
public class AccountDO {

    private String id;
    private String memberId;
    private String memberName;
    private String type;
    private String status;
    private Long createdAt;

    // ---------- P1 additive（A-1/A-3/M-3） ----------

    /** A-1 BALANCE/DEPOSIT/SUB/MARKETING/PENDING_SETTLE（既有行迁移默认 BALANCE）。 */
    private String accountType;
    /** M-3 I/II/III（既有行迁移默认 I）。 */
    private String level;
    /** A-3 NONE/FULL/JUDICIAL。 */
    private String freezeType;
    private String freezeReason;
    private Long freezeAt;

    /** CAS 参数（非库列）：updateStatusEx 期望原状态。 */
    private String expectedStatus;

    // ---------- P2 additive（A-4 多币种） ----------

    /** A-4 账户币种（ISO 4217，每账户单币种）；既有行缺省 CNY，行为逐字节不变。 */
    private String currency;

    // ---------- P2 G 泳道 additive（A-5 生命周期） ----------

    /** A-5 ACTIVE/CLOSING/CLOSED（既有行缺省 ACTIVE，行为逐字节不变；销户不物理删）。 */
    private String lifecycleStatus;
    /** A-5 销户时间（毫秒）。 */
    private Long closedAt;
}