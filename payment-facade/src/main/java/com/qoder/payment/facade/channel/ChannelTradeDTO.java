package com.qoder.payment.facade.channel;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class ChannelTradeDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String bizNo;
    private String type;
    private String origBizNo;
    private String cardNo;
    private Long amount;
    private String status;
    private String reason;
    private Long createdAt;
}