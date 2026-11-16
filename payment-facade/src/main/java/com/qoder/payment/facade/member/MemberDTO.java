package com.qoder.payment.facade.member;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class MemberDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String type;
    private String status;
    private String appId;
    private String appSecret;
    private Long createdAt;
}