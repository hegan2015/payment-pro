package com.qoder.payment.facade.account;

import com.qoder.payment.common.result.Result;

import java.util.List;
import java.util.Map;

/** 账户域：账户生命周期（开户/冻结），余额权威在账务域。对标支付宝账户系统。 */
public interface AccountFacade {

    Result<AccountDTO> create(String memberId);

    Result<List<AccountDTO>> list(String memberId);

    Result<AccountDTO> get(String accountId);

    Result<AccountDTO> getByMember(String memberId);

    Result<AccountDTO> updateStatus(String accountId, String status);

    /** 账务域记账前批量校验账户存在性与状态。 */
    Result<Map<String, AccountStatusDTO>> check(List<String> accountIds);
}