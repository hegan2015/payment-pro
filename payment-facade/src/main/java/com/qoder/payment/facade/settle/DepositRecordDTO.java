package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * ST-6 保证金单据（<b>一张表三类行</b>，只 INSERT + 受控 CAS，绝不物理删）：
 * <ul>
 *   <li>{@code type=DEPOSIT}：缴存单。amount=缴存额，releasedAmount/deductedAmount 累计消耗，
 *       status FROZEN→PARTIAL→RELEASED/DEDUCTED/MIXED（CAS 乐观并发，超额释放/扣划一律拒绝）；</li>
 *   <li>{@code type=RELEASE}：释放单（refId=原缴存单 id，immutable，status=SETTLED）；</li>
 *   <li>{@code type=DEDUCT}：扣划单（refId=原缴存单 id，immutable，status=SETTLED）。</li>
 * </ul>
 * 每行都有 request_id 全局唯一幂等键与确定性账务 bizNo（DP:/DR:/DD: 前缀）。
 */
@Data
public class DepositRecordDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String merchantId;
    /** 出资/退回账户（缺省商户 DEPOSIT 专户，A-1 类型户）。 */
    private String accountId;
    /** DEPOSIT / RELEASE / DEDUCT。 */
    private String type;
    /** RELEASE/DEDUCT 指向的原缴存单 id。 */
    private String refId;
    private Long amount;
    private Long releasedAmount;
    private Long deductedAmount;
    /** DEPOSIT: FROZEN/PARTIAL/RELEASED/DEDUCTED/MIXED；RELEASE/DEDUCT: SETTLED。 */
    private String status;
    /** 账务凭证号（确定性派生自 requestId）。 */
    private String bizNo;
    private String requestId;
    private String operator;
    private String reason;
    /** 扣划去向账户（SYS_FEE 平台收入 / SYS_ADVANCE 偿还垫资）。 */
    private String targetAccount;
    /** 扣划偿还的垫资单 id（可空）。 */
    private String advanceId;
    private Long createdAt;
}