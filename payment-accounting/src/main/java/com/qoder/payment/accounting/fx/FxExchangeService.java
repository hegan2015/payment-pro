package com.qoder.payment.accounting.fx;

import com.qoder.payment.accounting.dataobject.FxExchangeDO;
import com.qoder.payment.accounting.dataobject.FxRateDO;
import com.qoder.payment.accounting.mapper.FxExchangeMapper;
import com.qoder.payment.accounting.service.AccountingService;
import com.qoder.payment.accounting.subject.AccountSubjectMapping;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.payment.facade.account.AccountStatusDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.accounting.ExchangeRequest;
import com.qoder.payment.facade.accounting.ExchangeResultDTO;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.tcc.IdempotentTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A-4 换汇 + AC-6 跨币种平衡（<b>资金红线全复用既有 post</b>）。
 *
 * <p><b>跨币种平衡机制</b>：一次换汇 = <b>两张同币种凭证</b>，各自账套内 D=C 且 Σbalance=0，
 * <b>绝不把不同币种金额直接相加</b>：
 * <pre>
 * from 凭证（fromCurrency，bizNo-F）:
 *     D fromAccount (amount+fee)   C SYS_CASH[from] amount   C SYS_FEE[from] fee
 * to   凭证（toCurrency，bizNo-T）:
 *     D SYS_CASH[to] grossTo       C toAccount netTo         C SYS_FX[to] fxGain
 * </pre>
 * grossTo = amount×midRate/scale（账面中间价折算）；netTo = amount×buyRate/scale（客户实收，平台买入 from 价）；
 * fxGain = grossTo − netTo → <b>汇兑损益 SYS_FX（6601，to 币种账套）</b>。
 * 手续费在 from 币种额外收取 → <b>SYS_FEE（6001，与支付手续费同一口径同一科目）</b>，
 * 不新增系统户/科目映射（既有 11 条内置固定映射与 18 条科目目录逐条不变）。
 *
 * <p><b>幂等</b>：{@code (EXCHANGE, bizNo)}；两张凭证号确定性派生（bizNo-F / bizNo-T），
 * 受 VoucherGuard 只追加护栏保护 ⇒ 宕机重放不重复过账、不重复动账。
 * <p><b>原子性</b>：两张凭证 + 换汇单在同一本地事务（IdempotentTemplate），任一步失败整体回滚。
 * <p><b>汇率快照</b>：成交版本的 buy/sell/mid/scale/version/source/effectiveAt 全量落 fx_exchange，
 * 分录另落 currency→本位币折算快照（exchange_rate/base_amount）；后续汇率变动不影响已入账。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FxExchangeService {

    private final AccountingService accountingService;
    private final FxRateService fxRateService;
    private final FxExchangeMapper fxExchangeMapper;
    private final IdempotentTemplate idempotentTemplate;
    private final DynamicConfig dynamicConfig;

    @DubboReference
    private AccountFacade accountFacade;

    public ExchangeResultDTO exchange(ExchangeRequest request) {
        if (request == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "请求体必填");
        }
        String bizNo = request.getBizNo();
        if (bizNo == null || bizNo.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "bizNo 必填（换汇幂等键）");
        }
        if (bizNo.length() > 60) {
            throw new BizException(ResultCode.PARAM_ERROR, "bizNo 过长（≤60，需为两张派生凭证号留位）");
        }
        String from = request.getFromAccountId();
        String to = request.getToAccountId();
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "fromAccountId / toAccountId 必填");
        }
        if (from.equals(to)) {
            throw new BizException(ResultCode.PARAM_ERROR, "换汇需两个不同账户（同账户同币种）");
        }
        long amount = MoneyUtil.requireValid(request.getAmount() == null ? 0L : request.getAmount());
        String fromCcy = currencyOf(from);
        String toCcy = currencyOf(to);
        if (fromCcy.equals(toCcy)) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "同币种划转不走换汇（请用 transfer/post）: " + fromCcy);
        }
        int feeBps = request.getFeeBps() != null ? request.getFeeBps()
                : dynamicConfig.getInt(SandboxFxRateSource.KEY_FEE_BPS, 0);
        if (feeBps < 0 || feeBps > 10000) {
            throw new BizException(ResultCode.PARAM_ERROR, "feeBps 需在 0 ~ 10000");
        }
        long fee = MoneyUtil.fee(amount, feeBps);
        FxRateDO rate = fxRateService.require(fromCcy, toCcy, request.getRateVersion());
        int scale = rate.getRateScale();
        long grossTo = FxMath.convert(amount, rate.getMidRate(), scale);
        long netTo = FxMath.convert(amount, rate.getBuyRate(), scale);
        if (grossTo <= 0 || netTo <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "折算后金额需为正整数（分）: grossTo=" + grossTo + " netTo=" + netTo);
        }
        MoneyUtil.requireValid(grossTo);
        MoneyUtil.requireValid(netTo);
        long fxGain = grossTo - netTo;
        String cashFrom = AccountSubjectMapping.systemAccountFor(AccountingService.SYS_CASH, fromCcy);
        String cashTo = AccountSubjectMapping.systemAccountFor(AccountingService.SYS_CASH, toCcy);
        String fxAccount = AccountSubjectMapping.systemAccountFor(AccountingService.SYS_FX, toCcy);
        String feeAccount = AccountSubjectMapping.systemAccountFor(AccountingService.SYS_FEE, fromCcy);
        String fromBizNo = bizNo + "-F";
        String toBizNo = bizNo + "-T";
        String subject = request.getSubject() == null || request.getSubject().isBlank()
                ? "换汇 " + fromCcy + "→" + toCcy : request.getSubject();

        return idempotentTemplate.execute("EXCHANGE", bizNo, ExchangeResultDTO.class, () -> {
            FxExchangeDO existing = fxExchangeMapper.selectByBizNo(bizNo);
            if (existing != null) {
                return toDTO(existing);
            }
            // ---- from 币种凭证（单币种内 D=C）----
            List<TransferLineDTO> fromLines = new ArrayList<>();
            fromLines.add(line(from, "D", amount + fee));
            fromLines.add(line(cashFrom, "C", amount));
            if (fee > 0) {
                fromLines.add(line(feeAccount, "C", fee));
            }
            accountingService.post(fromBizNo, fromLines, subject + "(卖出腿)", bizNo, false,
                    com.qoder.payment.accounting.service.VoucherGuard.NORMAL, null, baseTag(fromCcy));
            // ---- to 币种凭证（单币种内 D=C，汇兑损益落 SYS_FX）----
            List<TransferLineDTO> toLines = new ArrayList<>();
            toLines.add(line(cashTo, "D", grossTo));
            toLines.add(line(to, "C", netTo));
            if (fxGain > 0) {
                toLines.add(line(fxAccount, "C", fxGain));
            } else if (fxGain < 0) {
                toLines.add(line(fxAccount, "D", -fxGain));
            }
            accountingService.post(toBizNo, toLines, subject + "(买入腿)", bizNo, false,
                    com.qoder.payment.accounting.service.VoucherGuard.NORMAL, null, baseTag(toCcy));

            FxExchangeDO row = new FxExchangeDO();
            row.setBizNo(bizNo);
            row.setFromBizNo(fromBizNo);
            row.setToBizNo(toBizNo);
            row.setFromAccountId(from);
            row.setToAccountId(to);
            row.setFromCurrency(fromCcy);
            row.setToCurrency(toCcy);
            row.setAmount(amount);
            row.setFee(fee);
            row.setFeeBps(feeBps);
            row.setGrossToAmount(grossTo);
            row.setNetToAmount(netTo);
            row.setFxGain(fxGain);
            row.setFxGainAccount(fxAccount);
            row.setFeeAccount(feeAccount);
            row.setBuyRate(rate.getBuyRate());
            row.setSellRate(rate.getSellRate());
            row.setMidRate(rate.getMidRate());
            row.setRateScale(scale);
            row.setRateVersion(rate.getVersion());
            row.setRateSource(rate.getSource());
            row.setRateEffectiveAt(rate.getEffectiveAt());
            row.setFromBalanceAfter(accountingService.balance(from));
            row.setToBalanceAfter(accountingService.balance(to));
            row.setOperator(request.getOperator());
            row.setCreatedAt(System.currentTimeMillis());
            try {
                fxExchangeMapper.insert(row);
            } catch (DuplicateKeyException e) {
                FxExchangeDO winner = fxExchangeMapper.selectByBizNo(bizNo);
                if (winner != null) {
                    return toDTO(winner);
                }
                throw e;
            }
            log.info("A-4 换汇 bizNo={} {}→{} amount={} fee={} grossTo={} netTo={} fxGain={} rateV={} 凭证={}/{}",
                    bizNo, fromCcy, toCcy, amount, fee, grossTo, netTo, fxGain, rate.getVersion(), fromBizNo, toBizNo);
            return toDTO(row);
        });
    }

    public ExchangeResultDTO get(String bizNo) {
        FxExchangeDO row = fxExchangeMapper.selectByBizNo(bizNo);
        if (row == null) {
            throw new BizException(ResultCode.NOT_FOUND, "换汇单不存在: " + bizNo);
        }
        return toDTO(row);
    }

    /** 分录本位币折算快照（currency → CNY）；CNY 恒 1:1，无汇率则拒绝入账（绝不臆造）。 */
    private AccountingService.FxTag baseTag(String currency) {
        long[] base = fxRateService.baseRate(currency);
        if (base == null) {
            throw new BizException(ResultCode.BIZ_ERROR, "缺少本位币折算汇率，禁止换汇入账: " + currency);
        }
        return new AccountingService.FxTag(currency, base[0], (int) base[1]);
    }

    /** 账户币种：系统户按命名约定，客户账户读账户域属性（存量缺省 CNY）。 */
    private String currencyOf(String accountId) {
        if (AccountSubjectMapping.isSystem(accountId)) {
            return AccountSubjectMapping.currencyOfSystemAccount(accountId);
        }
        Map<String, AccountStatusDTO> statuses = accountFacade.check(List.of(accountId)).orThrow();
        AccountStatusDTO status = statuses.get(accountId);
        if (status == null) {
            throw new BizException(ResultCode.NOT_FOUND, "账户不存在: " + accountId);
        }
        if (!"ACTIVE".equals(status.getStatus())) {
            throw new BizException(ResultCode.BIZ_ERROR, "账户已冻结: " + accountId);
        }
        String ccy = status.getCurrency();
        return ccy == null || ccy.isBlank() ? FxRateService.BASE_CURRENCY : ccy.trim().toUpperCase(Locale.ROOT);
    }

    private static TransferLineDTO line(String accountId, String dir, long amount) {
        TransferLineDTO line = new TransferLineDTO();
        line.setAccountId(accountId);
        line.setDir(dir);
        line.setAmount(amount);
        return line;
    }

    private static ExchangeResultDTO toDTO(FxExchangeDO row) {
        ExchangeResultDTO dto = new ExchangeResultDTO();
        dto.setBizNo(row.getBizNo());
        dto.setFromBizNo(row.getFromBizNo());
        dto.setToBizNo(row.getToBizNo());
        dto.setFromAccountId(row.getFromAccountId());
        dto.setToAccountId(row.getToAccountId());
        dto.setFromCurrency(row.getFromCurrency());
        dto.setToCurrency(row.getToCurrency());
        dto.setAmount(row.getAmount());
        dto.setFee(row.getFee());
        dto.setFeeBps(row.getFeeBps());
        dto.setGrossToAmount(row.getGrossToAmount());
        dto.setNetToAmount(row.getNetToAmount());
        dto.setFxGain(row.getFxGain());
        dto.setFxGainAccount(row.getFxGainAccount());
        dto.setFeeAccount(row.getFeeAccount());
        dto.setBuyRate(row.getBuyRate());
        dto.setSellRate(row.getSellRate());
        dto.setMidRate(row.getMidRate());
        dto.setRateScale(row.getRateScale());
        dto.setRateVersion(row.getRateVersion());
        dto.setRateSource(row.getRateSource());
        dto.setRateEffectiveAt(row.getRateEffectiveAt());
        dto.setFromBalanceAfter(row.getFromBalanceAfter());
        dto.setToBalanceAfter(row.getToBalanceAfter());
        dto.setCreatedAt(row.getCreatedAt());
        return dto;
    }
}