package com.qoder.payment.trade.service;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.member.MemberCreateRequest;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.member.MemberFacade;
import com.qoder.payment.trade.dataobject.TradeSystemAccountDO;
import com.qoder.payment.trade.mapper.TradeSystemAccountMapper;
import com.qoder.payment.trade.product.TradeProductKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * T-1 平台担保户（只调 member/account 既有 facade，绝不新增跨域接口）。
 *
 * <p><b>沿用支付核心 SYS_FUND 先例</b>：系统商户（MERCHANT，名 {@code SYS_ESCROW}）+ 其余额账户
 * 即平台担保户。担保支付的收款方就是该户（支付核心 {@code accountingContext} 按订单 merchantId
 * 取收款账户，故担保单 merchantId=系统商户、真实卖家落 payee_merchant_id）——
 * 由此 <b>无需改动 payment 域一行代码</b> 即实现「付款入担保户、卖家未入账」，
 * 且完整保留 TCC/风控/手续费/结算单/通知/对账链路。
 *
 * <p>懒创建 + 登记闸门：{@code trade_system_account(name PK)} 先占位再补全（CAS account_id IS NULL），
 * 多实例并发只有一户生效；进程内 synchronized 收敛同实例竞态。登记后零 RPC 直读。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EscrowHoldAccountService {

    /** 担保户登记名（系统商户名）。 */
    public record Hold(String memberId, String accountId) {
    }

    private final TradeSystemAccountMapper mapper;
    private final DynamicConfig dynamicConfig;

    @DubboReference
    private MemberFacade memberFacade;

    @DubboReference
    private AccountFacade accountFacade;

    public String holdMerchantName() {
        return dynamicConfig.get(TradeProductKeys.ESCROW_HOLD_MERCHANT, TradeProductKeys.DEFAULT_ESCROW_HOLD_MERCHANT);
    }

    /** 幂等确保担保户存在并返回（登记命中时零 RPC）。 */
    public synchronized Hold ensure() {
        String name = holdMerchantName();
        TradeSystemAccountDO row = mapper.selectByName(name);
        if (row != null && row.getAccountId() != null && !row.getAccountId().isBlank()) {
            return new Hold(row.getMemberId(), row.getAccountId());
        }
        long now = System.currentTimeMillis();
        if (row == null) {
            try {
                mapper.insertPlaceholder(name, now);
            } catch (DuplicateKeyException e) {
                log.info("担保户登记占位竞态（他实例创建中）name={}", name);
            }
        }
        MemberDTO merchant = memberFacade.list().orThrow().stream()
                .filter(m -> name.equals(m.getName()) && "MERCHANT".equals(m.getType()))
                .findFirst().orElse(null);
        if (merchant == null) {
            MemberCreateRequest request = new MemberCreateRequest();
            request.setName(name);
            request.setType("MERCHANT");
            merchant = memberFacade.create(request).orThrow();
            log.info("创建平台担保系统商户 name={} memberId={}", name, merchant.getId());
        }
        AccountDTO account = accountFacade.list(merchant.getId()).orThrow().stream().findFirst().orElse(null);
        if (account == null) {
            account = accountFacade.create(merchant.getId()).orThrow();
        }
        mapper.complete(name, merchant.getId(), account.getId(), System.currentTimeMillis());
        // 只认胜者：并发下后到者创建的孤儿会员不承载任何资金（后续一律读登记行）
        TradeSystemAccountDO winner = mapper.selectByName(name);
        if (winner == null || winner.getAccountId() == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "担保户登记失败: " + name);
        }
        log.info("平台担保户就绪 name={} memberId={} accountId={}", name, winner.getMemberId(), winner.getAccountId());
        return new Hold(winner.getMemberId(), winner.getAccountId());
    }

    /** 只读担保户账户 id（未创建返回 null，供查询/对账展示）。 */
    public Hold find() {
        TradeSystemAccountDO row = mapper.selectByName(holdMerchantName());
        return row == null || row.getAccountId() == null ? null : new Hold(row.getMemberId(), row.getAccountId());
    }
}