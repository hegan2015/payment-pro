package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * ST-6 垫资单据（平台先垫付后追偿）：
 * <ul>
 *   <li>{@code type=ADVANCE}：垫资单。D SYS_ADVANCE / C 商户账户；status ADVANCED→PARTIAL→RECOVERED；</li>
 *   <li>{@code type=RECOVER}：追偿单（refId=原垫资单 id，immutable）。D 商户账户 / C SYS_ADVANCE。</li>
 * </ul>
 * 幂等键 (ADVANCE,requestId) / (ADVANCE_RECOVER,requestId)；账务 bizNo AD:/AR: 确定性派生。
 */
@Data
public class AdvanceRecordDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String merchantId;
    /** 垫资入账/追偿出账账户（缺省商户 BALANCE 户）。 */
    private String accountId;
    /** ADVANCE / RECOVER。 */
    private String type;
    private String refId;
    private Long amount;
    private Long recoveredAmount;
    /** ADVANCE: ADVANCED/PARTIAL/RECOVERED；RECOVER: SETTLED。 */
    private String status;
    private String bizNo;
    private String requestId;
    private String operator;
    private String reason;
    private Long createdAt;
    private Long updatedAt;
}