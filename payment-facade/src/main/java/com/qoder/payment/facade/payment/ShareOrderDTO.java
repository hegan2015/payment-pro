package com.qoder.payment.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** P-3 分账单视图（含明细）。status：PENDING/SHARED/BACKING/BACKED。 */
@Data
public class ShareOrderDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String paymentId;
    private String merchantId;
    private String merchantAccountId;
    private Long totalAmount;
    private String shareType;
    private String status;
    private String requestId;
    private String bizNo;
    private Long executeAt;
    private Long createdAt;
    private Long sharedAt;
    private Long backedAt;
    private String failReason;
    private List<Detail> details;

    @Data
    public static class Detail implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String id;
        private String receiverAccountId;
        private Long amount;
        /** PENDING/SHARED/BACKED。 */
        private String status;
        private String bizNo;
    }
}