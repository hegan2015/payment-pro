package com.qoder.payment.facade.member;

import com.qoder.payment.common.result.Result;

import java.util.List;

/** 会员域：用户/商户注册、状态管理、OpenAPI 密钥。对标支付宝会员系统。 */
public interface MemberFacade {

    Result<MemberDTO> create(MemberCreateRequest request);

    Result<List<MemberDTO>> list();

    Result<MemberDTO> get(String memberId);

    Result<MemberDTO> getByAppId(String appId);

    Result<MemberDTO> updateStatus(String memberId, String status);

    // ---------- M-1 密钥轮换 / 多密钥 / 证书 ----------

    /**
     * 轮换 appSecret：生成新版本 ACTIVE，旧版置 RETIRED 保留宽限期（graceSeconds，null 用服务端默认）。
     * 返回 MemberDTO 一次性携带新 appSecret；list/get 一律脱敏（E-1 不回退）。
     */
    Result<MemberDTO> rotateSecret(String memberId, Long graceSeconds);

    /** 密钥版本列表：仅版本/状态/指纹，绝不返回明文。 */
    Result<List<SecretVersionDTO>> listSecretVersions(String memberId);

    /** 上传商户应用公钥证书（PEM），解析有效期/公钥/序列号。 */
    Result<CertDTO> uploadCert(String memberId, String certPem);

    Result<List<CertDTO>> listCerts(String memberId);

    /** 到期扫描：剩余天数 < warnDays 的 ACTIVE 证书 → 告警（ERROR 日志 + 指标），返回告警列表。 */
    Result<List<CertAlertDTO>> scanCerts(Integer warnDays);

    /** 查询剩余天数 < days 的证书（只读，不告警）。 */
    Result<List<CertAlertDTO>> expiringCerts(Integer days);

    // ---------- P1 additive（M-2/M-4/X-2/M-5；既有方法签名与语义不变，E-1 不回退） ----------

    /** M-2 提交 KYC 四要素（沙箱核验）；(KYC,memberId) 幂等：PASS 后重复提交返回原结果，FAIL 可修正重提。 */
    Result<KycResultDTO> submitKyc(KycRequest request);

    /** M-2 查询 KYC（脱敏出参）；未提交 A0404。 */
    Result<KycResultDTO> getKyc(String memberId);

    /** M-4 进件提交：(MERCHANT_APP,licenseNo) 幂等防重；SUBMITTED 并生成 X-2 审核任务。 */
    Result<MerchantApplicationDTO> submitApplication(ApplicationSubmitRequest request);

    Result<MerchantApplicationDTO> getApplication(String applicationId);

    Result<List<MerchantApplicationDTO>> listApplications(String status);

    /** M-4 驳回/补件后重提：REJECTED/SUPPLEMENT→SUBMITTED，生成新审核任务（历史留痕）。 */
    Result<MerchantApplicationDTO> resubmitApplication(String applicationId, ApplicationSubmitRequest request);

    /** M-4 签约（需 APPROVED）：产品/费率落 merchant_contract，一单一约幂等。 */
    Result<MerchantContractDTO> signContract(String applicationId, String products, Integer feeBps, String operator);

    /** UI additive（只读）：合同列表，merchantId 可选过滤（空=全量，上限 500，按签约时间倒序）。 */
    Result<List<MerchantContractDTO>> listContracts(String merchantId);

    /** UI additive（只读）：合同详情；不存在 A0404。 */
    Result<MerchantContractDTO> getContract(String contractId);

    /** M-4 结算卡绑定（需 APPROVED）：Luhn+BIN 沙箱校验，卡号 hash+mask；(merchantId,cardNoHash) 幂等。 */
    Result<SettleCardDTO> bindSettleCard(String applicationId, String cardNo, String bankName, String operator);

    Result<List<SettleCardDTO>> listSettleCards(String merchantId);

    /** X-2 审核任务列表（status 可选）。 */
    Result<List<ReviewTaskDTO>> listReviewTasks(String status);

    /** X-2 审核通过：任务 APPROVED + 进件 APPROVED + 创建 MERCHANT 会员（appSecret 仅本次返回）。 */
    Result<MerchantApplicationDTO> approveApplication(String applicationId, String reviewer, String opinion);

    /** X-2 驳回：留痕原因，可重提。 */
    Result<MerchantApplicationDTO> rejectApplication(String applicationId, String reviewer, String reason);

    /** X-2 补件：SUPPLEMENT 状态，商户补齐后重提。 */
    Result<MerchantApplicationDTO> supplementApplication(String applicationId, String reviewer, String opinion);

    /** M-5 C 端注册（USER 会员 + 口令凭证；手机号唯一）。 */
    Result<MemberDTO> memberRegister(MemberRegisterRequest request);

    /** M-5 短信验证码（沙箱）：返回 sandboxCode 仅供联调；60s 间隔限频，5 分钟有效。 */
    Result<String> sendSmsCode(String mobile);

    /** M-5 C 端登录（口令/短信沙箱）：失败计数 5 次锁 15 分钟；冻结会员拒绝。 */
    Result<MemberTokenDTO> memberLogin(MemberLoginRequest request);

    /** M-5 登出：会话立即失效。 */
    Result<Boolean> memberLogout(String token);

    /** M-5 会话查询：无效/过期返回 valid=false。 */
    Result<MemberSessionDTO> getMemberSession(String token);

    /** M-5 改密：校验旧口令 + 审计；成功后该会员全部会话失效。 */
    Result<Boolean> changePassword(String memberId, String oldPassword, String newPassword, String operator);
}