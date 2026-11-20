package com.qoder.payment.facade.sign;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * CH-6 签约请求（用户授权商户按期扣款）。
 *
 * <p>卡号明文只入参：落库一律 {@code card_no_hash}(SHA-256) + {@code card_no_mask}(后四位) +
 * {@code card_no_cipher}(AES-GCM，主密钥经 SecretProvider)，<b>明文绝不落库/落日志</b>。
 */
@Data
public class SignRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 被扣款账户（授权人）。 */
    private String accountId;
    /** 授权商户（代扣发起方）。 */
    private String merchantId;
    /** 签约渠道（可空=DynamicConfig channel.route.default-channel，默认 MOCK_BANK）。 */
    private String channelCode;
    /** 银行卡号（明文入参，Luhn 校验；沙箱卡）。 */
    private String cardNo;
    /** 幂等键（必填）：sign_contract.request_id UNIQUE。 */
    private String requestId;

    /** 单笔代扣限额（分，可空=DynamicConfig sign.default.single-limit）。 */
    private Long singleLimit;
    /** 周期累计限额（分，可空=sign.default.period-limit）。 */
    private Long periodLimit;
    /** 周期口径：DAY（自然日）/ MONTH（自然月）/ NONE（不限周期），缺省 DAY。 */
    private String periodType;
    /** 生效时间（epoch 毫秒，可空=now）。 */
    private Long effectiveAt;
    /** 失效时间（epoch 毫秒，可空=now+sign.default.valid-days）。 */
    private Long expiredAt;
    /** 扣款日（1-28，可空=任意日可代扣）；非扣款日新代扣请求受理前拒绝。 */
    private Integer deductDay;
    /** 扣款失败最大重试次数（可空=sign.default.max-retry，默认 3）。 */
    private Integer maxRetry;
    /** 重试基础间隔（毫秒，可空=sign.default.retry-interval-ms，默认 60000；按尝试次数线性退避）。 */
    private Long retryIntervalMs;

    /** 签约/解约事件回调地址（可空=PULL 模式，notify 域既有语义）。 */
    private String callbackUrl;
    /** 商户 appId（事件签名取该商户密钥，可空=平台共享密钥）。 */
    private String appId;
    /** 操作员（控制台恒取会话用户，OpenAPI 侧为商户标识）。 */
    private String operator;
    /** 授权范围补充说明（落 authorized_scope JSON 留痕，可空）。 */
    private String scopeNote;
}