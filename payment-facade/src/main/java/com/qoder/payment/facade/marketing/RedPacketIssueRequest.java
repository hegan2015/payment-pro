package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** X-3a 发红包请求。幂等键 {@code (REDPACKET_ISSUE, bizNo)} + {@code red_packet.request_id} UNIQUE。 */
@Data
public class RedPacketIssueRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String memberId;
    private String templateId;
    private Long amount;
    private Long expireAt;
    private Integer validDays;
    private String bizNo;
    private String operator;
}