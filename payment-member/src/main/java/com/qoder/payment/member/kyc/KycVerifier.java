package com.qoder.payment.member.kyc;

/**
 * M-2 四要素核验 SPI：姓名+身份证+银行卡+手机号一致性核验。
 * local/沙箱默认 {@link LocalKycVerifier}（规则模拟，零依赖）；
 * docker 接真实核验服务时以同类型 Bean 覆盖（@Primary 或排除本地实现），业务代码不变。
 */
public interface KycVerifier {

    /** 核验结果：pass + 失败原因（原因不得携带敏感要素明文）。 */
    record VerifyResult(boolean pass, String reason) {
    }

    VerifyResult verify(String name, String idCardNo, String bankCardNo, String mobile);
}