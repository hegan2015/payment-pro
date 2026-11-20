package com.qoder.payment.marketing.dataobject;

import lombok.Data;

/** X-3b 积分批次行（FIFO 消耗 + 按批过期；remaining 原子扣减）。 */
@Data
public class PointsBatchDO {

    private String id;
    private String memberId;
    private Long amount;
    private Long remaining;
    private Long expireAt;
    /** ACTIVE / EXHAUSTED / EXPIRED。 */
    private String status;
    private String bizNo;
    private Long createdAt;
    private Long updatedAt;
}