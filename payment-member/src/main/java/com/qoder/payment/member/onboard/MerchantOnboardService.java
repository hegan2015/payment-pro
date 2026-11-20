package com.qoder.payment.member.onboard;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.account.BankCardUtil;
import com.qoder.payment.facade.account.IdCardUtil;
import com.qoder.payment.facade.auth.AuditWriteRequest;
import com.qoder.payment.facade.member.ApplicationSubmitRequest;
import com.qoder.payment.facade.member.MemberCreateRequest;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.member.MerchantApplicationDTO;
import com.qoder.payment.facade.member.MerchantContractDTO;
import com.qoder.payment.facade.member.SettleCardDTO;
import com.qoder.payment.member.dataobject.MerchantApplicationDO;
import com.qoder.payment.member.dataobject.MerchantContractDO;
import com.qoder.payment.member.dataobject.MerchantSettleCardDO;
import com.qoder.payment.member.dataobject.ReviewTaskDO;
import com.qoder.payment.member.mapper.MerchantApplicationMapper;
import com.qoder.payment.member.mapper.MerchantContractMapper;
import com.qoder.payment.member.mapper.MerchantSettleCardMapper;
import com.qoder.payment.member.mapper.ReviewTaskMapper;
import com.qoder.payment.member.service.AuditService;
import com.qoder.payment.member.service.MemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * M-4 商户进件：提交→审核（X-2）→签约→结算卡。
 * <ul>
 *   <li>状态机 DRAFT→SUBMITTED→APPROVED/REJECTED/SUPPLEMENT（补件），驳回/补件可重提；</li>
 *   <li>幂等 (MERCHANT_APP,licenseNo)：同执照号重复进件返回既有单；</li>
 *   <li>审核通过创建 MERCHANT 会员（appId/appSecret 经 SecretService 密文落版本表，
 *       明文仅 approve 当次返回一次，E-1 口径）；</li>
 *   <li>营业执照号哈希+掩码、法人/联系方式掩码，资质 JSON 留痕，全程审计。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantOnboardService {

    private final MerchantApplicationMapper applicationMapper;
    private final MerchantContractMapper contractMapper;
    private final MerchantSettleCardMapper settleCardMapper;
    private final ReviewTaskMapper reviewTaskMapper;
    private final MemberService memberService;
    private final AuditService auditService;

    @Transactional
    public MerchantApplicationDTO submit(ApplicationSubmitRequest request) {
        validate(request);
        String licenseHash = BankCardUtil.sha256(request.getLicenseNo().trim().toUpperCase());
        MerchantApplicationDO existing = applicationMapper.selectByLicenseHash(licenseHash);
        if (existing != null) {
            // 幂等防重：同执照号返回既有进件单（重提走 resubmit）
            audit("APP_SUBMIT_DUP", existing.getId(), existing.getStatus(), "licenseNo 已进件，返回既有单");
            return toDTO(existing, null);
        }
        long now = System.currentTimeMillis();
        MerchantApplicationDO app = new MerchantApplicationDO();
        app.setId("MA" + applicationMapper.nextId());
        fill(app, request, licenseHash);
        app.setStatus("SUBMITTED");
        app.setCreatedAt(now);
        app.setUpdatedAt(now);
        try {
            applicationMapper.insert(app);
        } catch (DuplicateKeyException e) {
            return toDTO(applicationMapper.selectByLicenseHash(licenseHash), null);
        }
        newPendingTask(app.getId(), now);
        audit("APP_SUBMIT", app.getId(), "SUBMITTED", "merchant=" + app.getMerchantName()
                + " license=" + app.getLicenseNoMask());
        log.info("进件提交 applicationId={} merchant={} status=SUBMITTED", app.getId(), app.getMerchantName());
        return toDTO(app, null);
    }

    public MerchantApplicationDTO get(String applicationId) {
        return toDTO(require(applicationId), null);
    }

    public List<MerchantApplicationDTO> list(String status) {
        List<MerchantApplicationDO> apps = status == null || status.isBlank()
                ? applicationMapper.selectAll() : applicationMapper.selectByStatus(status.trim().toUpperCase());
        return apps.stream().map(a -> toDTO(a, null)).toList();
    }

    @Transactional
    public MerchantApplicationDTO resubmit(String applicationId, ApplicationSubmitRequest request) {
        MerchantApplicationDO app = require(applicationId);
        if (!"REJECTED".equals(app.getStatus()) && !"SUPPLEMENT".equals(app.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "仅 REJECTED/SUPPLEMENT 可重提，当前: " + app.getStatus());
        }
        validate(request);
        long now = System.currentTimeMillis();
        app.setMerchantName(request.getMerchantName().trim());
        app.setLegalPersonMask(IdCardUtil.maskName(request.getLegalPerson()));
        app.setContactMask(maskContact(request.getContact()));
        app.setQualification(request.getQualification());
        app.setStatus("SUBMITTED");
        app.setRejectReason(null);
        app.setUpdatedAt(now);
        applicationMapper.updateResubmit(app);
        newPendingTask(app.getId(), now);
        audit("APP_RESUBMIT", app.getId(), "SUBMITTED", "重提进件，历史审核任务留痕");
        return toDTO(app, null);
    }

    /** X-2 审核通过回调：创建 MERCHANT 会员 + 进件 APPROVED；appSecret 仅本次返回。 */
    @Transactional
    public MerchantApplicationDTO onApproved(String applicationId, String reviewer) {
        MerchantApplicationDO app = require(applicationId);
        if (!"SUBMITTED".equals(app.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "仅 SUBMITTED 可审核通过，当前: " + app.getStatus());
        }
        MemberCreateRequest create = new MemberCreateRequest();
        create.setName(app.getMerchantName());
        create.setType("MERCHANT");
        MemberDTO member = memberService.create(create);
        long now = System.currentTimeMillis();
        app.setStatus("APPROVED");
        app.setMemberId(member.getId());
        app.setReviewedAt(now);
        app.setUpdatedAt(now);
        applicationMapper.updateReview(app);
        audit("APP_APPROVED", app.getId(), "APPROVED", "reviewer=" + reviewer + " memberId=" + member.getId());
        log.info("进件审核通过 applicationId={} memberId={} reviewer={}", app.getId(), member.getId(), reviewer);
        return toDTO(app, member.getAppSecret());
    }

    @Transactional
    public MerchantContractDTO signContract(String applicationId, String products, Integer feeBps, String operator) {
        MerchantApplicationDO app = require(applicationId);
        requireApproved(app);
        if (products == null || products.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "products 必填（如 BALANCE,CARD）");
        }
        if (feeBps != null && (feeBps < 0 || feeBps > 10000)) {
            throw new BizException(ResultCode.PARAM_ERROR, "feeBps 须在 0..10000");
        }
        MerchantContractDO existing = contractMapper.selectByApplicationId(applicationId);
        if (existing != null) {
            return toContractDTO(existing);
        }
        MerchantContractDO contract = new MerchantContractDO();
        contract.setId("MC" + contractMapper.nextId());
        contract.setApplicationId(applicationId);
        contract.setMerchantId(app.getMemberId());
        contract.setProducts(products.trim());
        contract.setFeeBps(feeBps);
        contract.setStatus("SIGNED");
        contract.setOperator(operator);
        contract.setSignedAt(System.currentTimeMillis());
        try {
            contractMapper.insert(contract);
        } catch (DuplicateKeyException e) {
            return toContractDTO(contractMapper.selectByApplicationId(applicationId));
        }
        audit("CONTRACT_SIGN", applicationId, "SIGNED", "products=" + products + " feeBps=" + feeBps
                + " operator=" + operator);
        return toContractDTO(contract);
    }

    @Transactional
    public SettleCardDTO bindSettleCard(String applicationId, String cardNo, String bankName, String operator) {
        MerchantApplicationDO app = require(applicationId);
        requireApproved(app);
        if (!BankCardUtil.validCardNo(cardNo)) {
            throw new BizException(ResultCode.PARAM_ERROR, "结算卡号 Luhn/格式校验不通过");
        }
        String hash = BankCardUtil.sha256(cardNo);
        MerchantSettleCardDO existing = settleCardMapper.selectByMerchantAndHash(app.getMemberId(), hash);
        if (existing != null) {
            return toSettleCardDTO(existing);
        }
        MerchantSettleCardDO card = new MerchantSettleCardDO();
        card.setId("SC" + settleCardMapper.nextId());
        card.setMerchantId(app.getMemberId());
        card.setCardNoHash(hash);
        card.setCardNoMask(BankCardUtil.maskCard(cardNo));
        card.setBankName(bankName == null || bankName.isBlank() ? BankCardUtil.binBank(cardNo) : bankName.trim());
        card.setStatus(settleCardMapper.countDefault(app.getMemberId()) == 0 ? "DEFAULT" : "BOUND");
        card.setCreatedAt(System.currentTimeMillis());
        try {
            settleCardMapper.insert(card);
        } catch (DuplicateKeyException e) {
            return toSettleCardDTO(settleCardMapper.selectByMerchantAndHash(app.getMemberId(), hash));
        }
        audit("SETTLE_CARD_BIND", applicationId, card.getStatus(), "card=" + card.getCardNoMask()
                + " bank=" + card.getBankName() + " operator=" + operator);
        return toSettleCardDTO(card);
    }

    public List<SettleCardDTO> listSettleCards(String merchantId) {
        return settleCardMapper.selectByMerchantId(merchantId).stream()
                .map(MerchantOnboardService::toSettleCardDTO).toList();
    }

    /** UI additive（只读）：签约合同列表；merchantId 可选过滤，按签约时间倒序（上限 500）。 */
    public List<MerchantContractDTO> listContracts(String merchantId) {
        return contractMapper.selectList(merchantId == null || merchantId.isBlank() ? null : merchantId.trim())
                .stream().map(MerchantOnboardService::toContractDTO).toList();
    }

    /** UI additive（只读）：合同详情；不存在 A0404。 */
    public MerchantContractDTO getContract(String contractId) {
        MerchantContractDO contract = contractMapper.selectById(contractId);
        if (contract == null) {
            throw new BizException(ResultCode.NOT_FOUND, "合同不存在: " + contractId);
        }
        return toContractDTO(contract);
    }

    // ------------------------------------------------------------------ internal

    private void newPendingTask(String applicationId, long now) {
        ReviewTaskDO task = new ReviewTaskDO();
        task.setId("RT" + reviewTaskMapper.nextId());
        task.setApplicationId(applicationId);
        task.setStatus("PENDING");
        task.setCreatedAt(now);
        reviewTaskMapper.insert(task);
    }

    private MerchantApplicationDO require(String applicationId) {
        MerchantApplicationDO app = applicationMapper.selectById(applicationId);
        if (app == null) {
            throw new BizException(ResultCode.NOT_FOUND, "进件单不存在: " + applicationId);
        }
        return app;
    }

    private static void requireApproved(MerchantApplicationDO app) {
        if (!"APPROVED".equals(app.getStatus()) || app.getMemberId() == null) {
            throw new BizException(ResultCode.CONFLICT, "进件未审核通过，不可签约/绑卡");
        }
    }

    private static void validate(ApplicationSubmitRequest request) {
        if (request == null || request.getMerchantName() == null || request.getMerchantName().isBlank()
                || request.getMerchantName().length() > 50) {
            throw new BizException(ResultCode.PARAM_ERROR, "merchantName 必填且不超过 50 字");
        }
        if (request.getLicenseNo() == null || !request.getLicenseNo().trim().matches("[0-9A-Za-z]{15,20}")) {
            throw new BizException(ResultCode.PARAM_ERROR, "licenseNo 须为 15-20 位字母数字（统一社会信用代码）");
        }
        if (request.getLegalPerson() == null || request.getLegalPerson().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "legalPerson 必填");
        }
        String contact = request.getContact();
        if (contact == null || !(BankCardUtil.validMobile(contact.trim())
                || contact.trim().matches("[\\w.+-]+@[\\w-]+\\.[\\w.]+"))) {
            throw new BizException(ResultCode.PARAM_ERROR, "contact 须为手机号或邮箱");
        }
    }

    private static void fill(MerchantApplicationDO app, ApplicationSubmitRequest request, String licenseHash) {
        String license = request.getLicenseNo().trim().toUpperCase();
        app.setMerchantName(request.getMerchantName().trim());
        app.setLicenseNoHash(licenseHash);
        app.setLicenseNoMask(license.substring(0, 4) + "**********" + license.substring(license.length() - 4));
        app.setLegalPersonMask(IdCardUtil.maskName(request.getLegalPerson()));
        app.setContactMask(maskContact(request.getContact()));
        app.setQualification(request.getQualification());
        app.setSubmitter(request.getSubmitter());
    }

    private static String maskContact(String contact) {
        if (contact == null) {
            return null;
        }
        String trimmed = contact.trim();
        if (BankCardUtil.validMobile(trimmed)) {
            return BankCardUtil.maskMobile(trimmed);
        }
        int at = trimmed.indexOf('@');
        if (at > 1) {
            return trimmed.charAt(0) + "***" + trimmed.substring(at);
        }
        return "***";
    }

    private void audit(String action, String bizNo, String result, String detail) {
        AuditWriteRequest audit = new AuditWriteRequest();
        audit.setUsername("onboard-service");
        audit.setAction(action);
        audit.setBizNo(bizNo);
        audit.setResult(result);
        audit.setDetail(detail);
        auditService.write(audit);
    }

    private static MerchantApplicationDTO toDTO(MerchantApplicationDO app, String oneTimeSecret) {
        MerchantApplicationDTO dto = new MerchantApplicationDTO();
        dto.setId(app.getId());
        dto.setMerchantName(app.getMerchantName());
        dto.setLicenseNoMask(app.getLicenseNoMask());
        dto.setLegalPersonMask(app.getLegalPersonMask());
        dto.setContactMask(app.getContactMask());
        dto.setQualification(app.getQualification());
        dto.setStatus(app.getStatus());
        dto.setRejectReason(app.getRejectReason());
        dto.setMemberId(app.getMemberId());
        dto.setSubmitter(app.getSubmitter());
        dto.setCreatedAt(app.getCreatedAt());
        dto.setReviewedAt(app.getReviewedAt());
        if (app.getMemberId() != null) {
            dto.setAppId("app" + app.getMemberId());
        }
        dto.setAppSecret(oneTimeSecret);
        return dto;
    }

    private static MerchantContractDTO toContractDTO(MerchantContractDO contract) {
        MerchantContractDTO dto = new MerchantContractDTO();
        dto.setId(contract.getId());
        dto.setApplicationId(contract.getApplicationId());
        dto.setMerchantId(contract.getMerchantId());
        dto.setProducts(contract.getProducts());
        dto.setFeeBps(contract.getFeeBps());
        dto.setStatus(contract.getStatus());
        dto.setOperator(contract.getOperator());
        dto.setSignedAt(contract.getSignedAt());
        return dto;
    }

    private static SettleCardDTO toSettleCardDTO(MerchantSettleCardDO card) {
        SettleCardDTO dto = new SettleCardDTO();
        dto.setId(card.getId());
        dto.setMerchantId(card.getMerchantId());
        dto.setCardNoMask(card.getCardNoMask());
        dto.setBankName(card.getBankName());
        dto.setStatus(card.getStatus());
        dto.setCreatedAt(card.getCreatedAt());
        return dto;
    }
}