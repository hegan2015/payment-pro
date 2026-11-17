package com.qoder.payment.facade.accounting;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.common.PageDTO;

import java.util.List;
import java.util.Map;

/**
 * 账务域：复式记账（有借必有贷、借贷必相等）、余额权威、交易流水、试算平衡。
 * 余额 = 贷方累计 - 借方累计；可用余额 = 余额 - 冻结。对标支付宝账务系统。
 */
public interface AccountingFacade {

    /** 充值：外部现金入户。bizNo 幂等，返回充值后余额。 */
    Result<Long> topup(TopupRequest request);

    /** 通用过账（转账/提现等）：bizNo 幂等，借贷平衡校验。 */
    Result<Void> post(PostRequest request);

    Result<Long> balance(String accountId);

    Result<Map<String, Long>> balances(List<String> accountIds);

    Result<List<EntryDTO>> entries(String accountId, int limit);

    /** 全局分页流水（跨账户），筛选可选。 */
    Result<PageDTO<EntryDTO>> entriesPage(EntryQuery query);

    Result<TransferDTO> transfer(String bizNo);

    Result<TrialDTO> trial();
}