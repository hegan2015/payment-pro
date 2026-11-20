package com.qoder.payment.facade.openplatform;

import com.qoder.payment.common.result.Result;

import java.util.List;

/**
 * G-4a/G-4b 开放平台域契约（<b>全新子包</b>，实现在 payment-member，不动任何既有域 facade）。
 *
 * <p>职责：应用注册/授权 scope/撤销、策略解析（网关内部）、按 appId 配额与调用计费账本、
 * 沙箱独立账本（账户/资金单/交易单）。
 *
 * <p><b>资金隔离</b>：本域所有写操作只落 member 库 openapi_* 表，
 * 绝不调用 accounting/payment/trade  facade，故计费与沙箱<b>不产生任何生产资金变动</b>。
 */
public interface OpenPlatformFacade {

    // ---------- 应用注册与授权 ----------

    /** 注册应用：沙箱生成独立 appId+密钥（明文仅本次返回）；生产绑定既有商户 appId。appId 重复 → A0409。 */
    Result<OpenAppDTO> registerApp(OpenAppRegisterRequest request);

    /** 查询应用（脱敏，E-1 口径）。 */
    Result<OpenAppDTO> getApp(String appId);

    /** 应用列表（脱敏）。 */
    Result<List<OpenAppDTO>> listApps();

    /** 调整 scope/配额/单价/默认版本（null 字段不改）。 */
    Result<OpenAppDTO> updateApp(OpenAppUpdateRequest request);

    /** 撤销授权：status=REVOKED，网关即时拒绝（策略缓存同步失效 + token 吊销）。 */
    Result<OpenAppDTO> revokeApp(String appId, String operator);

    /** 恢复授权：status=ACTIVE。 */
    Result<OpenAppDTO> activateApp(String appId, String operator);

    /**
     * 网关内部策略解析（含沙箱验签密钥）。未注册 appId → {@link AppPolicyDTO#legacy(String)}，
     * 不抛异常——历史商户（ride-pro）零影响。
     */
    Result<AppPolicyDTO> resolvePolicy(String appId);

    // ---------- 配额与计费 ----------

    Result<ApiQuotaDTO> getQuota(String appId);

    /** 日账单（bizDate 为空=当日；appId 为空=全部应用）。 */
    Result<List<ApiBillDTO>> getBill(String appId, String bizDate);

    /** 网关异步批量上报调用增量（累加计数 × 单价 → billedAmount；只写开放平台账本）。 */
    Result<Boolean> recordCalls(List<ApiCallDelta> deltas);

    // ---------- 沙箱账本（与生产资金完全隔离） ----------

    /** 沙箱开户（name+type 幂等，返回 SBXM / SBXA 前缀的独立 id）。 */
    Result<SandboxAccountDTO> sandboxOpenAccount(String appId, String name, String type);

    /** 沙箱余额（裸 long，形状与 v1 account.balance 一致）。 */
    Result<Long> sandboxBalance(String appId, String accountId);

    /** 沙箱充值/提现资金单：kind=TOPUP/WITHDRAW，(appId,bizNo) 幂等，fee=0、同步 SUCCESS。 */
    Result<SandboxTxnDTO> sandboxFund(String appId, String kind, String accountId, Long amount, String bizNo);

    /** 沙箱定向划转：(appId,bizNo) 幂等，借贷两腿在沙箱账本内完成。 */
    Result<Boolean> sandboxTransfer(String appId, String fromAccountId, String toAccountId, Long amount, String bizNo);

    /** 沙箱下单：(appId,outTradeNo) 幂等。 */
    Result<SandboxOrderDTO> sandboxCreateOrder(String appId, String subject, Long amount, String outTradeNo);

    /** 沙箱支付：method 仅 BALANCE，同步 SUCCESS（与 v1 BALANCE 分支语义一致），bizNo 幂等。 */
    Result<SandboxTxnDTO> sandboxPay(String appId, String orderId, String accountId, String method, String bizNo);

    Result<SandboxOrderDTO> sandboxGetOrder(String appId, String orderId);

    Result<SandboxTxnDTO> sandboxGetTxn(String appId, String txnId);

    /** 沙箱账户列表（联调自查，只读）。 */
    Result<List<SandboxAccountDTO>> sandboxListAccounts(String appId);

    /** UI-B additive：沙箱交易单列表（只读，倒序；limit 默认 50 上限 200）。 */
    Result<List<SandboxOrderDTO>> sandboxListOrders(String appId, Integer limit);

    /** UI-B additive：沙箱资金单/支付单列表（只读，倒序；limit 默认 50 上限 200）。 */
    Result<List<SandboxTxnDTO>> sandboxListTxns(String appId, Integer limit);
}