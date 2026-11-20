package com.qoder.payment.facade.member;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * M-2 KYC 四要素提交（沙箱核验，不接真实公安/银行）。
 * <b>敏感红线</b>：idCardNo/bankCardNo/mobile 仅传输用，服务端哈希落库、脱敏出参，绝不落明文/日志。
 */
@Data
public class KycRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String memberId;
    private String name;
    private String idCardNo;
    private String bankCardNo;
    private String mobile;
}