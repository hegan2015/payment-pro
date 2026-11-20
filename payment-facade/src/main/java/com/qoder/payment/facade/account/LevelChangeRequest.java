package com.qoder.payment.facade.account;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * A-5 账户等级升降级请求（M-3 分级限额联动）。
 *
 * <p>升级：复用 M-3 既有校验（II 需 KYC 四要素 PASS，III 需 KYC PASS + operator 复核），只升不降语义不变。
 * <p>降级：A-5 新增。需 operator 复核 + 原因；<b>降级后余额不得超目标等级余额上限</b>（否则拒绝，
 * 避免限额下调造成既有资金超限）；CLOSED/CLOSING 账户一律拒绝。全程 account_lifecycle_log 留痕。
 */
@Data
public class LevelChangeRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String accountId;
    /** 目标等级 I/II/III。 */
    private String targetLevel;
    private String operator;
    private String reason;
}