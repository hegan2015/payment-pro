package com.qoder.payment.facade.sign;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** CH-6 代扣请求：凭协议号扣款，金额/幂等键必填。 */
@Data
public class AgentPayRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 协议号（必填）。 */
    private String signNo;
    /** 扣款金额（分，必填，>0 且 ≤1e12）。 */
    private Long amount;
    /** 幂等键（必填）：sign_deduct.request_id UNIQUE ⇒ 重复请求返回首次扣款单。 */
    private String requestId;
    /** 发起商户（可空）：非空时校验与协议授权商户一致（授权范围校验）。 */
    private String merchantId;
    /** 交易标题（可空，缺省「代扣-{signNo}」）。 */
    private String subject;
    /** 支付结果回调（可空，缺省用协议 callbackUrl；经既有 payment→notify 路径投递 PAY_SUCCESS）。 */
    private String callbackUrl;
    /** 操作员（控制台恒取会话用户）。 */
    private String operator;
}