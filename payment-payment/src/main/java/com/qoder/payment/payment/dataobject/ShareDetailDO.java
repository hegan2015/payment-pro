package com.qoder.payment.payment.dataobject;

import lombok.Data;

/** P-3 分账明细（share_detail，UNIQUE(share_id,receiver_account_id)）。 */
@Data
public class ShareDetailDO {

    private String id;
    private String shareId;
    private String receiverAccountId;
    private Long amount;
    /** PENDING/SHARED/BACKED。 */
    private String status;
    private String bizNo;
    private Long createdAt;
}