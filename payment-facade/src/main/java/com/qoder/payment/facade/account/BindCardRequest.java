package com.qoder.payment.facade.account;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * A-2 绑卡请求（四要素沙箱核验）。
 * <b>敏感红线</b>：cardNo/idCardNo/mobile 仅传输用，服务端立即 hash+mask，绝不落库/落日志。
 */
@Data
public class BindCardRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String accountId;
    private String cardNo;
    private String mobile;
    private String name;
    /** 可选：提供则做身份证校验位核验。 */
    private String idCardNo;
    /** 可选：缺省按沙箱卡 BIN 推断。 */
    private String bankName;
    private String operator;
}