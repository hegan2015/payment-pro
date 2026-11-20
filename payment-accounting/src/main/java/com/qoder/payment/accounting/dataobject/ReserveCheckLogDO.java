package com.qoder.payment.accounting.dataobject;

import lombok.Data;

/** S-7 备付金账实核对留痕（reserve_check_log）。 */
@Data
public class ReserveCheckLogDO {

    private String id;
    private Long bookBalance;
    private Long actualBalance;
    private Long diff;
    private Boolean pass;
    private Long checkedAt;
    /** AC-5 会计日（additive；P0 check() 路径为 null）。 */
    private Long bizDate;
    /** AC-5 渠道（additive；null 等价 MOCK_BANK）。 */
    private String channelCode;
    /** AC-5 差异分级 NONE/WARN/ERROR/CRITICAL（additive）。 */
    private String level;
    /** AC-5 长短款 NONE/LONG/SHORT（additive）。 */
    private String direction;
    /** AC-5 差错单号（additive）。 */
    private String disputeNo;
}