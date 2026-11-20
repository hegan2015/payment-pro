package com.qoder.payment.member.facade;

import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.member.ApplicationSubmitRequest;
import com.qoder.payment.facade.member.CertAlertDTO;
import com.qoder.payment.facade.member.CertDTO;
import com.qoder.payment.facade.member.KycRequest;
import com.qoder.payment.facade.member.KycResultDTO;
import com.qoder.payment.facade.member.MemberCreateRequest;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.member.MemberFacade;
import com.qoder.payment.facade.member.MemberLoginRequest;
import com.qoder.payment.facade.member.MemberRegisterRequest;
import com.qoder.payment.facade.member.MemberSessionDTO;
import com.qoder.payment.facade.member.MemberTokenDTO;
import com.qoder.payment.facade.member.MerchantApplicationDTO;
import com.qoder.payment.facade.member.MerchantContractDTO;
import com.qoder.payment.facade.member.ReviewTaskDTO;
import com.qoder.payment.facade.member.SecretVersionDTO;
import com.qoder.payment.facade.member.SettleCardDTO;
import com.qoder.payment.member.auth.MemberAuthService;
import com.qoder.payment.member.kyc.KycService;
import com.qoder.payment.member.onboard.MerchantOnboardService;
import com.qoder.payment.member.onboard.ReviewFlowService;
import com.qoder.payment.member.service.CertService;
import com.qoder.payment.member.service.MemberService;
import com.qoder.payment.member.service.SecretService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
@RequiredArgsConstructor
public class MemberFacadeImpl implements MemberFacade {

    private final MemberService memberService;
    private final SecretService secretService;
    private final CertService certService;
    private final KycService kycService;
    private final MerchantOnboardService onboardService;
    private final ReviewFlowService reviewFlowService;
    private final MemberAuthService memberAuthService;

    @Override
    public Result<MemberDTO> create(MemberCreateRequest request) {
        return FacadeSupport.execute(() -> memberService.create(request));
    }

    @Override
    public Result<List<MemberDTO>> list() {
        return FacadeSupport.execute(memberService::list);
    }

    @Override
    public Result<MemberDTO> get(String memberId) {
        return FacadeSupport.execute(() -> memberService.get(memberId));
    }

    @Override
    public Result<MemberDTO> getByAppId(String appId) {
        return FacadeSupport.execute(() -> memberService.getByAppId(appId));
    }

    @Override
    public Result<MemberDTO> updateStatus(String memberId, String status) {
        return FacadeSupport.execute(() -> memberService.updateStatus(memberId, status));
    }

    @Override
    public Result<MemberDTO> rotateSecret(String memberId, Long graceSeconds) {
        return FacadeSupport.execute(() -> memberService.rotateSecret(memberId, graceSeconds));
    }

    @Override
    public Result<List<SecretVersionDTO>> listSecretVersions(String memberId) {
        return FacadeSupport.execute(() -> {
            memberService.get(memberId);
            return secretService.listVersions(memberId);
        });
    }

    @Override
    public Result<CertDTO> uploadCert(String memberId, String certPem) {
        return FacadeSupport.execute(() -> {
            memberService.get(memberId);
            return certService.upload(memberId, certPem);
        });
    }

    @Override
    public Result<List<CertDTO>> listCerts(String memberId) {
        return FacadeSupport.execute(() -> {
            memberService.get(memberId);
            return certService.list(memberId);
        });
    }

    @Override
    public Result<List<CertAlertDTO>> scanCerts(Integer warnDays) {
        return FacadeSupport.execute(() -> certService.scan(warnDays));
    }

    @Override
    public Result<List<CertAlertDTO>> expiringCerts(Integer days) {
        return FacadeSupport.execute(() -> certService.expiring(days));
    }

    // ---------- P1 additive（M-2/M-4/X-2/M-5） ----------

    @Override
    public Result<KycResultDTO> submitKyc(KycRequest request) {
        return FacadeSupport.execute(() -> kycService.submit(request));
    }

    @Override
    public Result<KycResultDTO> getKyc(String memberId) {
        return FacadeSupport.execute(() -> kycService.get(memberId));
    }

    @Override
    public Result<MerchantApplicationDTO> submitApplication(ApplicationSubmitRequest request) {
        return FacadeSupport.execute(() -> onboardService.submit(request));
    }

    @Override
    public Result<MerchantApplicationDTO> getApplication(String applicationId) {
        return FacadeSupport.execute(() -> onboardService.get(applicationId));
    }

    @Override
    public Result<List<MerchantApplicationDTO>> listApplications(String status) {
        return FacadeSupport.execute(() -> onboardService.list(status));
    }

    @Override
    public Result<MerchantApplicationDTO> resubmitApplication(String applicationId, ApplicationSubmitRequest request) {
        return FacadeSupport.execute(() -> onboardService.resubmit(applicationId, request));
    }

    @Override
    public Result<MerchantContractDTO> signContract(String applicationId, String products, Integer feeBps, String operator) {
        return FacadeSupport.execute(() -> onboardService.signContract(applicationId, products, feeBps, operator));
    }

    @Override
    public Result<SettleCardDTO> bindSettleCard(String applicationId, String cardNo, String bankName, String operator) {
        return FacadeSupport.execute(() -> onboardService.bindSettleCard(applicationId, cardNo, bankName, operator));
    }

    @Override
    public Result<List<MerchantContractDTO>> listContracts(String merchantId) {
        return FacadeSupport.execute(() -> onboardService.listContracts(merchantId));
    }

    @Override
    public Result<MerchantContractDTO> getContract(String contractId) {
        return FacadeSupport.execute(() -> onboardService.getContract(contractId));
    }

    @Override
    public Result<List<SettleCardDTO>> listSettleCards(String merchantId) {
        return FacadeSupport.execute(() -> onboardService.listSettleCards(merchantId));
    }

    @Override
    public Result<List<ReviewTaskDTO>> listReviewTasks(String status) {
        return FacadeSupport.execute(() -> reviewFlowService.listTasks(status));
    }

    @Override
    public Result<MerchantApplicationDTO> approveApplication(String applicationId, String reviewer, String opinion) {
        return FacadeSupport.execute(() -> reviewFlowService.approve(applicationId, reviewer, opinion));
    }

    @Override
    public Result<MerchantApplicationDTO> rejectApplication(String applicationId, String reviewer, String reason) {
        return FacadeSupport.execute(() -> reviewFlowService.reject(applicationId, reviewer, reason));
    }

    @Override
    public Result<MerchantApplicationDTO> supplementApplication(String applicationId, String reviewer, String opinion) {
        return FacadeSupport.execute(() -> reviewFlowService.supplement(applicationId, reviewer, opinion));
    }

    @Override
    public Result<MemberDTO> memberRegister(MemberRegisterRequest request) {
        return FacadeSupport.execute(() -> memberAuthService.register(request));
    }

    @Override
    public Result<String> sendSmsCode(String mobile) {
        return FacadeSupport.execute(() -> memberAuthService.sendSmsCode(mobile));
    }

    @Override
    public Result<MemberTokenDTO> memberLogin(MemberLoginRequest request) {
        return FacadeSupport.execute(() -> memberAuthService.login(request));
    }

    @Override
    public Result<Boolean> memberLogout(String token) {
        return FacadeSupport.execute(() -> memberAuthService.logout(token));
    }

    @Override
    public Result<MemberSessionDTO> getMemberSession(String token) {
        return FacadeSupport.execute(() -> memberAuthService.getSession(token));
    }

    @Override
    public Result<Boolean> changePassword(String memberId, String oldPassword, String newPassword, String operator) {
        return FacadeSupport.execute(() -> memberAuthService.changePassword(memberId, oldPassword, newPassword, operator));
    }
}