package com.qoder.payment.facade.account;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AccountDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String memberId;
    private String memberName;
    private String type;
    private String status;
    private Long balance;
    private Long createdAt;
}