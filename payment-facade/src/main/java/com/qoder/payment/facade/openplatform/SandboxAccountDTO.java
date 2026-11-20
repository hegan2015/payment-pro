package com.qoder.payment.facade.openplatform;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** G-4a 沙箱账户（独立账本，id 前缀 SBXA，与生产 account 表无任何关系）。 */
@Data
public class SandboxAccountDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String accountId;
    private String memberId;
    private String appId;
    private String name;
    private String type;
    private Long balance;
    private Long createdAt;
}