package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** ST-3 分账结算明细（type=SHARE/SHARE_BACK，net=amount-fee）。 */
@Data
public class ShareSettleDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String shareId;
    private String paymentId;
    private String merchantId;
    private String merchantAccountId;
    private String receiverAccountId;
    private Long amount;
    private Long fee;
    private Long net;
    private String type;
    private String status;
    private String bizNo;
    private Long createdAt;
}