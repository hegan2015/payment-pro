package com.qoder.payment.channel.facade;

import com.qoder.payment.channel.dataobject.SignAuditDO;
import com.qoder.payment.channel.dataobject.SignEventDO;
import com.qoder.payment.channel.mapper.SignAuditMapper;
import com.qoder.payment.channel.mapper.SignEventMapper;
import com.qoder.payment.channel.sign.SignConfigKeys;
import com.qoder.payment.channel.sign.SignContractService;
import com.qoder.payment.channel.sign.SignDeductService;
import com.qoder.payment.channel.sign.SignEventPublisher;
import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.sign.AgentPayRequest;
import com.qoder.payment.facade.sign.SignAuditDTO;
import com.qoder.payment.facade.sign.SignContractDTO;
import com.qoder.payment.facade.sign.SignDeductDTO;
import com.qoder.payment.facade.sign.SignEventDTO;
import com.qoder.payment.facade.sign.SignFacade;
import com.qoder.payment.facade.sign.SignQuotaDTO;
import com.qoder.payment.facade.sign.SignRequest;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.BeanUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * CH-6 签约代扣 + X-4 账户授权 Dubbo 实现（channel 域，全部经 {@link FacadeSupport#execute} 包装）。
 *
 * <p><b>新增独立 facade，不改 ChannelFacade</b>：既有渠道契约（TCC/查询/回执/路由/限额/账单）
 * 一字未动，避免跨泳道共享文件竞争；签约能力经新 {@code SignFacade} 暴露，
 * REST 仍挂 {@code /api/channel/sign*}（安全链既有 channel:read / channel:write 权限点天然覆盖）。
 */
@DubboService
@RequiredArgsConstructor
public class SignFacadeImpl implements SignFacade {

    private final SignContractService contractService;
    private final SignDeductService deductService;
    private final SignEventPublisher eventPublisher;
    private final SignEventMapper eventMapper;
    private final SignAuditMapper auditMapper;

    @Override
    public Result<SignContractDTO> signContract(SignRequest request) {
        return FacadeSupport.execute(() -> contractService.sign(request));
    }

    @Override
    public Result<SignContractDTO> unsignContract(String signNo, String requestId, String operator, String reason) {
        return FacadeSupport.execute(() -> contractService.unsign(signNo, requestId, operator, reason));
    }

    @Override
    public Result<SignContractDTO> getContract(String signNo) {
        return FacadeSupport.execute(() -> contractService.get(signNo));
    }

    @Override
    public Result<List<SignContractDTO>> listContracts(String accountId, String merchantId, String status) {
        return FacadeSupport.execute(() -> contractService.list(accountId, merchantId, status));
    }

    @Override
    public Result<SignQuotaDTO> getQuota(String signNo) {
        return FacadeSupport.execute(() -> contractService.quota(signNo));
    }

    @Override
    public Result<SignDeductDTO> agentPay(AgentPayRequest request) {
        return FacadeSupport.execute(() -> deductService.agentPay(request));
    }

    @Override
    public Result<SignDeductDTO> getDeduct(String deductNo) {
        return FacadeSupport.execute(() -> deductService.get(deductNo));
    }

    @Override
    public Result<List<SignDeductDTO>> listDeducts(String signNo, String status) {
        return FacadeSupport.execute(() -> deductService.list(signNo, status));
    }

    @Override
    public Result<SignDeductDTO> retryDeduct(String deductNo, String requestId, String operator) {
        return FacadeSupport.execute(() -> deductService.retry(deductNo, requestId, operator));
    }

    @Override
    public Result<SignDeductDTO> terminateDeduct(String deductNo, String operator, String reason) {
        return FacadeSupport.execute(() -> deductService.terminate(deductNo, operator, reason));
    }

    @Override
    public Result<Integer> runDeductRetry(int limit) {
        return FacadeSupport.execute(() -> deductService.runRetry(limit));
    }

    @Override
    public Result<Integer> runEventDispatch(int limit) {
        return FacadeSupport.execute(() -> eventPublisher.dispatchDue(limit));
    }

    @Override
    public Result<List<SignEventDTO>> listEvents(String signNo) {
        return FacadeSupport.execute(() -> {
            List<SignEventDTO> out = new ArrayList<>();
            for (SignEventDO row : eventMapper.selectBySignNo(signNo)) {
                SignEventDTO dto = new SignEventDTO();
                BeanUtils.copyProperties(row, dto);
                out.add(dto);
            }
            return out;
        });
    }

    @Override
    public Result<List<SignAuditDTO>> listAudit(String signNo, int limit) {
        return FacadeSupport.execute(() -> {
            int size = limit > 0 ? Math.min(limit, SignConfigKeys.LIST_LIMIT) : 50;
            List<SignAuditDTO> out = new ArrayList<>();
            for (SignAuditDO row : auditMapper.selectList(signNo == null || signNo.isBlank() ? null : signNo, size)) {
                SignAuditDTO dto = new SignAuditDTO();
                BeanUtils.copyProperties(row, dto);
                out.add(dto);
            }
            return out;
        });
    }

    @Override
    public Result<List<SignContractDTO>> listAuthorizations(String accountId) {
        return FacadeSupport.execute(() -> contractService.authorizations(accountId));
    }

    @Override
    public Result<SignContractDTO> revokeAuthorization(String accountId, String signNo, String requestId,
                                                       String operator) {
        return FacadeSupport.execute(() -> contractService.revoke(accountId, signNo, requestId, operator));
    }
}