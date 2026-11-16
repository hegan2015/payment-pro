package com.qoder.payment.facade.member;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class MemberCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String name;
    private String type;
}