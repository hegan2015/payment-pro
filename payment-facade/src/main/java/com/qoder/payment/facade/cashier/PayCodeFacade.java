package com.qoder.payment.facade.cashier;

import com.qoder.payment.common.result.Result;

/**
 * C-2 付款码/扫码（被扫 + 主扫）沙箱门面：码生成/解析/核销受理。
 * <b>沙箱边界</b>：不接真实微信/支付宝，码生成/校验/有效期/一次性/防重放均本地实现。
 */
public interface PayCodeFacade {

    /** 生成码：被扫 18 位数字码（用户出示）；主扫二维码令牌（商户出码）。 */
    Result<PayCodeDTO> createCode(CreateCodeRequest request);

    /** 解析码（只读校验有效期/状态，不核销）；过期/已核销返回错误码。 */
    Result<PayCodeDTO> parseCode(String code);

    /** 扫码受理：一次性核销（CAS 防重放）+ 支付验证 + 幂等支付。 */
    Result<com.qoder.payment.facade.payment.PayResultDTO> scanPay(ScanPayRequest request);
}