package com.qoder.payment.account.dataobject;

import lombok.Data;

@Data
public class AccountDO {

    private String id;
    private String memberId;
    private String memberName;
    private String type;
    private String status;
    private Long createdAt;
}