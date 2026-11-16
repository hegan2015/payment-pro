package com.qoder.payment.member.dataobject;

import lombok.Data;

@Data
public class MemberDO {

    private String id;
    private String name;
    private String type;
    private String status;
    private String appId;
    private String appSecret;
    private Long createdAt;
}