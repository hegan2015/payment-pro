package com.qoder.payment.member.openplatform;

import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.openplatform.ApiBillDTO;
import com.qoder.payment.facade.openplatform.ApiCallDelta;
import com.qoder.payment.facade.openplatform.ApiQuotaDTO;
import com.qoder.payment.facade.openplatform.AppPolicyDTO;
import com.qoder.payment.facade.openplatform.OpenAppDTO;
import com.qoder.payment.facade.openplatform.OpenAppRegisterRequest;
import com.qoder.payment.facade.openplatform.OpenAppUpdateRequest;
import com.qoder.payment.facade.openplatform.OpenPlatformFacade;
import com.qoder.payment.facade.openplatform.SandboxAccountDTO;
import com.qoder.payment.facade.openplatform.SandboxOrderDTO;
import com.qoder.payment.facade.openplatform.SandboxTxnDTO;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

/** G-4a 开放平台域实现（member 进程内，全新 facade，不影响既有 MemberFacade 一个字节）。 */
@DubboService
@RequiredArgsConstructor
public class OpenPlatformFacadeImpl implements OpenPlatformFacade {

    private final OpenPlatformService openPlatformService;
    private final SandboxService sandboxService;

    @Override
    public Result<OpenAppDTO> registerApp(OpenAppRegisterRequest request) {
        return FacadeSupport.execute(() -> openPlatformService.register(request));
    }

    @Override
    public Result<OpenAppDTO> getApp(String appId) {
        return FacadeSupport.execute(() -> openPlatformService.get(appId));
    }

    @Override
    public Result<List<OpenAppDTO>> listApps() {
        return FacadeSupport.execute(openPlatformService::list);
    }

    @Override
    public Result<OpenAppDTO> updateApp(OpenAppUpdateRequest request) {
        return FacadeSupport.execute(() -> openPlatformService.update(request));
    }

    @Override
    public Result<OpenAppDTO> revokeApp(String appId, String operator) {
        return FacadeSupport.execute(() -> openPlatformService.revoke(appId, operator));
    }

    @Override
    public Result<OpenAppDTO> activateApp(String appId, String operator) {
        return FacadeSupport.execute(() -> openPlatformService.activate(appId, operator));
    }

    @Override
    public Result<AppPolicyDTO> resolvePolicy(String appId) {
        return FacadeSupport.execute(() -> openPlatformService.resolvePolicy(appId));
    }

    @Override
    public Result<ApiQuotaDTO> getQuota(String appId) {
        return FacadeSupport.execute(() -> openPlatformService.quota(appId));
    }

    @Override
    public Result<List<ApiBillDTO>> getBill(String appId, String bizDate) {
        return FacadeSupport.execute(() -> openPlatformService.bill(appId, bizDate));
    }

    @Override
    public Result<Boolean> recordCalls(List<ApiCallDelta> deltas) {
        return FacadeSupport.execute(() -> openPlatformService.recordCalls(deltas));
    }

    @Override
    public Result<SandboxAccountDTO> sandboxOpenAccount(String appId, String name, String type) {
        return FacadeSupport.execute(() -> sandboxService.openAccount(appId, name, type));
    }

    @Override
    public Result<Long> sandboxBalance(String appId, String accountId) {
        return FacadeSupport.execute(() -> sandboxService.balance(appId, accountId));
    }

    @Override
    public Result<SandboxTxnDTO> sandboxFund(String appId, String kind, String accountId, Long amount, String bizNo) {
        return FacadeSupport.execute(() -> sandboxService.fund(appId, kind, accountId, amount, bizNo));
    }

    @Override
    public Result<Boolean> sandboxTransfer(String appId, String fromAccountId, String toAccountId, Long amount, String bizNo) {
        return FacadeSupport.execute(() -> sandboxService.transfer(appId, fromAccountId, toAccountId, amount, bizNo));
    }

    @Override
    public Result<SandboxOrderDTO> sandboxCreateOrder(String appId, String subject, Long amount, String outTradeNo) {
        return FacadeSupport.execute(() -> sandboxService.createOrder(appId, subject, amount, outTradeNo));
    }

    @Override
    public Result<SandboxTxnDTO> sandboxPay(String appId, String orderId, String accountId, String method, String bizNo) {
        return FacadeSupport.execute(() -> sandboxService.pay(appId, orderId, accountId, method, bizNo));
    }

    @Override
    public Result<SandboxOrderDTO> sandboxGetOrder(String appId, String orderId) {
        return FacadeSupport.execute(() -> sandboxService.getOrder(appId, orderId));
    }

    @Override
    public Result<SandboxTxnDTO> sandboxGetTxn(String appId, String txnId) {
        return FacadeSupport.execute(() -> sandboxService.getTxn(appId, txnId));
    }

    @Override
    public Result<List<SandboxAccountDTO>> sandboxListAccounts(String appId) {
        return FacadeSupport.execute(() -> sandboxService.listAccounts(appId));
    }

    @Override
    public Result<List<SandboxOrderDTO>> sandboxListOrders(String appId, Integer limit) {
        return FacadeSupport.execute(() -> sandboxService.listOrders(appId, limit));
    }

    @Override
    public Result<List<SandboxTxnDTO>> sandboxListTxns(String appId, Integer limit) {
        return FacadeSupport.execute(() -> sandboxService.listTxns(appId, limit));
    }
}