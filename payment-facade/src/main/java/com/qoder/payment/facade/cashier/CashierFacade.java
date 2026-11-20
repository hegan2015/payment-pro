package com.qoder.payment.facade.cashier;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.payment.PayRequest;
import com.qoder.payment.facade.payment.PayResultDTO;

/** 收银台：聚合交易单与可用支付方式，提交支付到支付核心。对标支付宝收银台。 */
public interface CashierFacade {

    Result<CashierDTO> cashier(String orderId);

    /** C-1 指定付款人渲染（账户级可用性：冻结/余额不足/签约实时判定）；additive，不改 cashier 聚合语义。 */
    Result<CashierDTO> cashierForPayer(String orderId, String accountId);

    /**
     * P0 原始受理路径（<b>ride 红线：BALANCE 同步语义逐字节不变</b>）。
     * 不引入验证前置/WAITING/异步回执；新验证流程走 {@link #payVerified}。
     */
    Result<PayResultDTO> pay(PayRequest request);

    // ---------- P1 C-3 additive（验证前置受理，新端点；不改 pay 行为） ----------

    /** C-3 验证前置的支付受理：校验 verifyToken/口令/小额免密后，以同 requestId 委托支付核心。 */
    Result<PayResultDTO> payVerified(VerifiedPayRequest request);

    /** C-3 支付验证（口令/短信沙箱/指纹模拟/小额免密），通过签发一次性 verifyToken。 */
    Result<VerifyResultDTO> verify(VerifyRequest request);

    /** C-3 设置/重置支付密码（沙箱，哈希加盐落 cashier 库）。 */
    Result<Boolean> setPayPassword(SetPayPasswordRequest request);

    /** C-3 发送短信验证码（沙箱，码值仅沙箱返回）；additive。 */
    Result<java.util.Map<String, Object>> sendSms(String accountId, String memberId, String orderId);
}