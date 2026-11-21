package com.qoder.payment.marketing.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.accounting.EntryDTO;
import com.qoder.payment.facade.accounting.EntryQuery;
import com.qoder.payment.facade.accounting.PostRequest;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.payment.facade.accounting.TrialDTO;
import com.qoder.payment.facade.common.PageDTO;
import com.qoder.payment.facade.marketing.AllocItemDTO;
import com.qoder.payment.facade.marketing.MarketingFundCheckDTO;
import com.qoder.payment.marketing.mapper.MarketingAllocMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * X-3 营销资金护栏（<b>不自建资金账本</b>）：一切真实资金变动经 {@link AccountingFacade#post} 复式记账。
 *
 * <p><b>分录口径</b>：核销 = {@code D SYS_MARKETING / C 用户抵扣户}（营销支出 6002 借增、用户余额贷增）；
 * 退回 = {@code D 用户抵扣户 / C SYS_MARKETING}（业务退款语义，非红冲；红冲 AC-1 仍归账务域差错纠正）。
 * 借贷恒等 ⇒ 账务试算 D=C 与 Σbalance=0 不被破坏；SYS_MARKETING 是系统户（透支语义），
 * 用户户借方受账务域可用余额校验（不足即拒绝，绝不静默透支客户资金）。
 *
 * <p><b>幂等</b>：bizNo 由业务键<b>确定性派生</b>（{@code MKT:}/{@code MKTR:} 前缀），
 * 即便营销域本地事务回滚后重试，账务域 VoucherGuard 也只动账一次（与 settle ST-3 同一护栏）。
 *
 * <p><b>手续费口径不变</b>：本服务只处理营销抵扣额，绝不触碰 gross/fee/net 与 feeBps→SYS_FEE。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketingFundService {

    public static final String SYS_MARKETING = "SYS_MARKETING";
    public static final String DIM_SOURCE = "SOURCE";
    public static final String DIM_TARGET = "TARGET";
    public static final String TYPE_USE = "USE";
    public static final String TYPE_REFUND = "REFUND";

    private final MarketingSupport support;
    private final MarketingAllocMapper allocMapper;

    @DubboReference
    private AccountingFacade accountingFacade;

    @DubboReference
    private AccountFacade accountFacade;

    /** 抵扣入账户：显式 accountId 优先，否则按 memberId 解析既有 BALANCE 户（不新建账户、不改账户域）。 */
    public String resolveAccount(String memberId, String accountId) {
        if (MarketingSupport.notBlank(accountId)) {
            return accountId;
        }
        MarketingSupport.require(MarketingSupport.notBlank(memberId), "memberId 与 accountId 至少填一个");
        AccountDTO account = accountFacade.getByMember(memberId).orThrow();
        MarketingSupport.requireFound(account, "会员无可用账户，无法承接营销抵扣: " + memberId);
        return account.getId();
    }

    /** 营销补贴动账：D SYS_MARKETING / C accountId。 */
    public void subsidy(String bizNo, String accountId, long amount, String subject, String refId) {
        MarketingSupport.requireKey(bizNo);
        MarketingSupport.requireBiz(amount > 0, "营销动账金额必须为正整数（分）");
        post(bizNo, SYS_MARKETING, accountId, amount, subject, refId);
    }

    /** 营销退回动账（反向）：D accountId / C SYS_MARKETING。 */
    public void subsidyBack(String bizNo, String accountId, long amount, String subject, String refId) {
        MarketingSupport.requireKey(bizNo);
        MarketingSupport.requireBiz(amount > 0, "营销退回金额必须为正整数（分）");
        // P-7 直补商户模型：退回目标=原补贴凭证的贷方户（商户）。从原 MKT 凭证解析贷方非系统户，
        // 兼容 standalone（贷方用户户）；原凭证不存在（补贴未落）则回退传入账户。
        String target = accountId;
        String origBizNo = bizNo.startsWith(MarketingSupport.FUND_REFUND_PREFIX)
                ? MarketingSupport.FUND_PREFIX + bizNo.substring(MarketingSupport.FUND_REFUND_PREFIX.length())
                : bizNo;
        try {
            com.qoder.payment.facade.accounting.VoucherDTO orig = accountingFacade.voucher(origBizNo).orThrow();
            if (orig != null && orig.getLines() != null) {
                for (com.qoder.payment.facade.accounting.TransferLineDTO line : orig.getLines()) {
                    if ("C".equals(line.getDir()) && !SYS_MARKETING.equals(line.getAccountId())) {
                        target = line.getAccountId();
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("营销退回解析原补贴凭证失败，回退传入账户 bizNo={} orig={} : {}", bizNo, origBizNo, e.getMessage());
        }
        post(bizNo, target, SYS_MARKETING, amount, subject, refId);
    }

    private void post(String bizNo, String debitAccount, String creditAccount, long amount,
                      String subject, String refId) {
        PostRequest request = new PostRequest();
        request.setBizNo(bizNo);
        request.setSubject(subject);
        request.setRefId(refId);
        request.setLines(List.of(line(debitAccount, "D", amount), line(creditAccount, "C", amount)));
        accountingFacade.post(request).orThrow();
        log.info("营销资金复式记账 bizNo={} D={} C={} amount={}", bizNo, debitAccount, creditAccount, amount);
    }

    private static TransferLineDTO line(String accountId, String dir, long amount) {
        TransferLineDTO line = new TransferLineDTO();
        line.setAccountId(accountId);
        line.setDir(dir);
        line.setAmount(amount);
        return line;
    }

    /**
     * 落来源维度分摊行（幂等 by biz_no UNIQUE）：券/红包/积分/活动核销与退回各落一行，
     * Σ(USE) − Σ(REFUND) 即该支付单的营销抵扣总额。
     */
    public void recordSource(String paymentId, String source, String refId, long amount, String type, String fundBizNo) {
        String bizNo = "AL:" + ("USE".equals(type) ? "U:" : "R:") + fundBizNo;
        MarketingSupport.require(bizNo.length() <= 128, "分摊幂等键过长");
        if (allocMapper.selectByBiz(bizNo) != null) {
            return;
        }
        AllocItemDTO item = new AllocItemDTO();
        item.setId(support.newId("AL"));
        item.setPaymentId(paymentId);
        item.setDimension(DIM_SOURCE);
        item.setSource(source);
        item.setTargetId(refId);
        item.setAmount(amount);
        item.setType(type);
        item.setBizNo(bizNo);
        item.setCreatedAt(System.currentTimeMillis());
        allocMapper.insert(item);
    }

    /** 该支付单来源维度营销抵扣净额（Σ核销 − Σ退回）。 */
    public long sourceNet(String paymentId) {
        return allocMapper.sum(paymentId, DIM_SOURCE, TYPE_USE) - allocMapper.sum(paymentId, DIM_SOURCE, TYPE_REFUND);
    }

    /** 该支付单目标维度分摊净额。 */
    public long targetNet(String paymentId) {
        return allocMapper.sum(paymentId, DIM_TARGET, TYPE_USE) - allocMapper.sum(paymentId, DIM_TARGET, TYPE_REFUND);
    }

    /**
     * 营销资金恒等核对（只读）：营销域<b>分摊来源账</b>（marketing_alloc SOURCE 维度，按资金 bizNo 去重，
     * 与 accounting 凭证 1:1）vs 账务域 SYS_MARKETING 真实分录（只统计 MKT 前缀凭证）
     * + 账务试算平衡（D=C 且 Σbalance=0）。任何一项不守恒即 balanced=false，供对账/告警接线。
     *
     * <p><b>口径</b>：{@code netUse} = 累计核销总额（gross，含后来被退回的核销）；
     * {@code netRefund} = 累计退回总额；{@code netMarketing = netUse − netRefund} = SYS_MARKETING 净流出账。
     * 之所以以 alloc 来源账为准而非各核销单状态求和：券/红包/活动退回时状态从 USED 迁到 REFUNDED
     * （求和需 USED+REFUNDED），而积分流水只追加（USE 行长存 + 另加 REFUND 行），两种形态混算会重复计数；
     * alloc 行以资金 bizNo 为唯一键，天然与凭证一一对应，是唯一无歧义的营销抵扣账。
     */
    public MarketingFundCheckDTO fundCheck() {
        long netUse = allocMapper.sumAll(DIM_SOURCE, TYPE_USE);
        long netRefund = allocMapper.sumAll(DIM_SOURCE, TYPE_REFUND);
        long netMarketing = netUse - netRefund;

        long debit = sumSysMarketing("D");
        long credit = sumSysMarketing("C");
        long outflow = debit - credit;
        Long balance = accountingFacade.balance(SYS_MARKETING).orThrow();

        TrialDTO trial = accountingFacade.trial().orThrow();
        MarketingFundCheckDTO dto = new MarketingFundCheckDTO();
        dto.setNetUse(netUse);
        dto.setNetRefund(netRefund);
        dto.setNetMarketing(netMarketing);
        dto.setSysMarketingDebit(debit);
        dto.setSysMarketingCredit(credit);
        dto.setSysMarketingOutflow(outflow);
        dto.setSysMarketingBalance(balance);
        dto.setDiff(netMarketing - outflow);
        dto.setTrialDebit(trial.getTotalDebit());
        dto.setTrialCredit(trial.getTotalCredit());
        dto.setTrialBalanced(trial.getBalanced());
        dto.setBalanceSum(trial.getBalanceSum());
        dto.setAccounts(trial.getAccounts());
        dto.setEntries(trial.getEntries() == null ? null : trial.getEntries().longValue());
        boolean balanced = dto.getDiff() == 0L
                && Boolean.TRUE.equals(trial.getBalanced())
                && MarketingSupport.nz(trial.getBalanceSum()) == 0L;
        dto.setBalanced(balanced);
        dto.setMessage(balanced ? "营销资金恒等成立：Σ核销−Σ退回 = SYS_MARKETING 出账，且试算 D=C、Σbalance=0"
                : "营销资金恒等/试算不平衡，diff=" + dto.getDiff()
                + " trialBalanced=" + trial.getBalanced() + " balanceSum=" + trial.getBalanceSum());
        return dto;
    }

    /** SYS_MARKETING 借/贷累计（只统计营销域 MKT 前缀凭证：核销 MKT: 与退回 MKTR:，分页归并不重不漏）。 */
    private long sumSysMarketing(String dir) {
        long total = 0L;
        int page = 1;
        int size = 200;
        while (page <= 500) {
            EntryQuery query = new EntryQuery();
            query.setAccountId(SYS_MARKETING);
            query.setDir(dir);
            query.setPage(page);
            query.setSize(size);
            PageDTO<EntryDTO> result = accountingFacade.entriesPage(query).orThrow();
            List<EntryDTO> items = result.getItems();
            if (items != null) {
                for (EntryDTO entry : items) {
                    if (entry.getBizNo() != null
                            && entry.getBizNo().startsWith(MarketingSupport.FUND_FILTER_PREFIX)) {
                        total += MarketingSupport.nz(entry.getAmount());
                    }
                }
            }
            if (items == null || items.size() < size || (long) page * size >= result.getTotal()) {
                break;
            }
            page++;
        }
        return total;
    }

    /** 营销域总开关（§5.5 回滚：禁用后核销一律拒绝，组合支付退化为纯资金腿）。 */
    public void requireEnabled() {
        if (!support.enabled()) {
            throw new BizException(ResultCode.BIZ_ERROR, "营销域已禁用（marketing.enabled=false），核销拒绝受理");
        }
    }
}