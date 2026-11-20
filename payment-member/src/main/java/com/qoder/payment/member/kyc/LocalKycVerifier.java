package com.qoder.payment.member.kyc;

import com.qoder.payment.facade.account.BankCardUtil;
import com.qoder.payment.facade.account.IdCardUtil;
import org.springframework.stereotype.Component;

/**
 * M-2 沙箱四要素核验（不接真实公安/工商/银行）：
 * 姓名非空 ≤50 字 + 身份证 18 位 GB11643 校验位 + 卡号 Luhn + 手机号 11 位格式。
 * 全部通过视为「四要素一致」（沙箱无跨源比对，格式核验即一致性模拟）。
 */
@Component
public class LocalKycVerifier implements KycVerifier {

    @Override
    public VerifyResult verify(String name, String idCardNo, String bankCardNo, String mobile) {
        if (name == null || name.isBlank() || name.length() > 50) {
            return new VerifyResult(false, "姓名必填且不超过 50 字");
        }
        if (!IdCardUtil.valid(idCardNo)) {
            return new VerifyResult(false, "身份证号校验位不通过");
        }
        if (!BankCardUtil.validCardNo(bankCardNo)) {
            return new VerifyResult(false, "银行卡号 Luhn 校验不通过");
        }
        if (!BankCardUtil.validMobile(mobile)) {
            return new VerifyResult(false, "手机号格式不通过");
        }
        return new VerifyResult(true, null);
    }
}