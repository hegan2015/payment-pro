package com.qoder.payment.member.openplatform;

import lombok.Data;

/** G-4a 沙箱账户（openapi_sandbox_account）：独立余额，与生产 account/account_balance 无关联。 */
@Data
public class SandboxAccountDO {

    private String accountId;
    private String appId;
    private String memberId;
    private String name;
    private String type;
    private Long balance;
    private Long createdAt;
}