package com.qoder.payment.facade.cashier;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * C-2 付款码/扫码 DTO（沙箱）。码值为随机数/令牌，<b>不嵌入账户口令等敏感信息</b>；
 * accountId/orderId/amount 为核销所需的业务标识，parseCode 返回前由服务端校验有效期与状态。
 */
@Data
public class PayCodeDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 码值：被扫=18 位数字（含校验位）；主扫=二维码内容令牌/URL。 */
    private String code;
    /** USER_PRESENTED（用户出示，商户扫）/ MERCHANT_PRESENTED（商户出码，用户扫）。 */
    private String type;
    /** 被扫：付款账户；主扫：收款商户账户。 */
    private String accountId;
    private String orderId;
    /** 金额（分）。 */
    private Long amount;
    /** ACTIVE / CONSUMED / EXPIRED。 */
    private String status;
    /** 过期时间（毫秒）。 */
    private Long expireAt;
    private Long createdAt;
    private Long consumedAt;
    /** 主扫二维码内容（供前端绘制 QR）；被扫为 null。 */
    private String qrContent;
    /** 展示用分组码值（被扫，前端条形码下方数字）；不含敏感信息。 */
    private String displayCode;
    private String merchantName;
    private String subject;
    /** 剩余有效秒数（服务端计算，供前端倒计时）。 */
    private Long ttlSeconds;
}