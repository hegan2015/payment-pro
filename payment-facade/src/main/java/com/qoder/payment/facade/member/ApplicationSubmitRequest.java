package com.qoder.payment.facade.member;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** M-4 商户进件提交/重提请求。licenseNo/legalPerson/contact 敏感字段哈希+脱敏存储。 */
@Data
public class ApplicationSubmitRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String merchantName;
    /** 统一社会信用代码/营业执照号（15-20 位字母数字，沙箱格式校验）。 */
    private String licenseNo;
    private String legalPerson;
    /** 联系手机号或邮箱。 */
    private String contact;
    /** 资质材料 JSON（附件 URL/说明等，原样留痕）。 */
    private String qualification;
    /** 提交人（控制台用户名，网关侧取会话覆盖）。 */
    private String submitter;
}