package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** X-3a 红包核销请求。幂等键 {@code (REDPACKET_USE, redPacketId:paymentId)} + red_packet_use 双 UNIQUE + CAS。 */
@Data
public class RedPacketUseRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String redPacketId;
    private String paymentId;
    private String orderId;
    private String memberId;
    private String accountId;
    private Long orderAmount;
    private Long amount;
    private String requestId;
    private String operator;
}