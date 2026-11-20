package com.qoder.payment.accounting.service;

import com.qoder.payment.accounting.dataobject.BalanceDO;
import com.qoder.payment.accounting.dataobject.EntryDO;
import com.qoder.payment.accounting.dataobject.TransferDO;
import com.qoder.payment.accounting.daycut.AccountingDayClock;
import com.qoder.payment.accounting.fx.FxMath;
import com.qoder.payment.accounting.fx.FxRateService;
import com.qoder.payment.accounting.mapper.BalanceMapper;
import com.qoder.payment.accounting.mapper.EntryMapper;
import com.qoder.payment.accounting.mapper.TransferMapper;
import com.qoder.payment.accounting.subject.AccountSubjectMapping;
import com.qoder.payment.accounting.subject.SubjectService;
import com.qoder.payment.common.db.IdGenerator;
import com.qoder.payment.common.db.ShardRowMirror;
import com.qoder.payment.common.db.ShardRuntime;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.payment.facade.accounting.PostRequest;
import com.qoder.payment.facade.accounting.ReverseRequest;
import com.qoder.payment.facade.accounting.ReverseResultDTO;
import com.qoder.tcc.IdempotentTemplate;
import com.qoder.tcc.TccContextUtil;
import com.qoder.payment.facade.account.AccountStatusDTO;
import com.qoder.payment.facade.accounting.AccountingTccContext;
import com.qoder.payment.facade.accounting.EntryDTO;
import com.qoder.payment.facade.accounting.EntryQuery;
import com.qoder.payment.facade.accounting.CurrencyTrialDTO;
import com.qoder.payment.facade.accounting.TopupRequest;
import com.qoder.payment.facade.accounting.TransferDTO;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.payment.facade.accounting.TrialDTO;
import com.qoder.payment.facade.common.PageDTO;
import com.qoder.payment.facade.account.AccountFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 账务核心：复式记账与余额权威。
 *
 * <p>余额 = 贷方累计 - 借方累计；可用余额 = 余额 - 冻结。
 * 系统户（SYS_ 前缀）允许透支；非系统户借方必须有足额可用余额。
 * 多账户加锁按 accountId 排序，避免并发转账死锁。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountingService {

    public static final String SYS_CASH = "SYS_CASH";
    public static final String SYS_CLEARING = "SYS_CLEARING";
    public static final String SYS_FEE = "SYS_FEE";
    /** S-6 差错挂账科目：承接长短款差额，待差错处理（X-1）蓝补核销。 */
    public static final String SYS_SUSPENSE = "SYS_SUSPENSE";
    /** A-4/AC-6 汇兑损益科目（6601）：换汇点差损益，按币种账套落 SYS_FX / SYS_FX_<CCY>。 */
    public static final String SYS_FX = "SYS_FX";
    /** AC-6 本位币（既有单币种账套）：存量 currency=NULL 一律按 CNY 读，口径逐字节不变。 */
    public static final String BASE_CURRENCY = "CNY";

    /**
     * AC-6 凭证级币种/汇率快照标签（<b>不进 lines JSON</b>，VoucherGuard 逐字比对不受影响）。
     * {@code null} 表示由账户币种解析 + 取当前本位币折算率（既有 CNY 路径即此分支）。
     */
    public record FxTag(String currency, Long exchangeRate, Integer rateScale) {
    }

    private final BalanceMapper balanceMapper;
    private final EntryMapper entryMapper;
    private final TransferMapper transferMapper;
    private final IdempotentTemplate idempotentTemplate;
    private final VoucherGuard voucherGuard;
    /** AC-2 科目解析（只产出 subject_code 标签，绝不参与余额/方向计算）。 */
    private final SubjectService subjectService;
    /** AC-3 会计日解析（在途 TCC 用受理时快照，日切不破坏在途单）。 */
    private final AccountingDayClock dayClock;
    /** A-4/AC-6 汇率与币种账套（只读快照；本位币 CNY 恒 1:1，既有路径零额外查询）。 */
    private final FxRateService fxRateService;
    /** I-5b：分片未装配（local / enabled=false）时全部为 null，走改造前原路径。 */
    private final ObjectProvider<ShardRuntime> shardRuntime;
    private final ObjectProvider<IdGenerator> idGenerator;
    private final ObjectProvider<ShardRowMirror> shardRowMirror;

    @DubboReference
    private AccountFacade accountFacade;

    public Long topup(TopupRequest request) {
        MoneyUtil.requireValid(request.getAmount());
        String bizNo = request.getBizNo();
        if (bizNo == null || bizNo.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "bizNo 必填");
        }
        checkAccounts(List.of(request.getAccountId()));
        List<TransferLineDTO> lines = List.of(
                line(SYS_CASH, "D", request.getAmount()),
                line(request.getAccountId(), "C", request.getAmount()));
        return idempotentTemplate.execute("TOPUP", bizNo, Long.class, () -> {
            post(bizNo, lines, "充值", null, false);
            log.info("充值过账 accountId={} amount={} bizNo={}", request.getAccountId(), request.getAmount(), bizNo);
            return balanceOf(request.getAccountId());
        });
    }

    public void postIdempotent(PostRequest request) {
        String bizNo = request.getBizNo();
        if (bizNo == null || bizNo.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "bizNo 必填");
        }
        idempotentTemplate.execute("POST", bizNo, Void.class, () -> {
            post(bizNo, request.getLines(), request.getSubject(), request.getRefId(), false,
                    VoucherGuard.inferType(bizNo), null, fxTagOf(request));
            log.info("通用过账 bizNo={} subject={} lines={}", bizNo, request.getSubject(), request.getLines().size());
            return null;
        });
    }

    /** AC-6：PostRequest 的币种/汇率快照标签（全空 → null，走改造前原路径）。 */
    private static FxTag fxTagOf(PostRequest request) {
        if (request.getCurrency() == null && request.getExchangeRate() == null && request.getRateScale() == null) {
            return null;
        }
        return new FxTag(request.getCurrency(), request.getExchangeRate(), request.getRateScale());
    }

    public Long balance(String accountId) {
        return balanceOf(accountId);
    }

    public Map<String, Long> balances(List<String> accountIds) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String id : accountIds) {
            result.put(id, balanceOf(id));
        }
        return result;
    }

    public List<EntryDTO> entries(String accountId, int limit) {
        int safeLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        return readYourWrite(() -> entryMapper.selectByAccount(accountId, safeLimit))
                .stream().map(AccountingService::toEntryDTO).toList();
    }

    /** 全局分页流水（跨账户），筛选可选。I-5b：跨片 ORDER BY seq DESC + LIMIT/OFFSET 由归并引擎处理。 */
    public PageDTO<EntryDTO> entriesPage(EntryQuery query) {
        query.setPage(Math.max(query.getPage(), 1));
        query.setSize(query.getSize() <= 0 ? 20 : Math.min(query.getSize(), 200));
        return readYourWrite(() -> {
            long total = entryMapper.countPage(query);
            List<EntryDTO> items = entryMapper.selectPage(query).stream().map(AccountingService::toEntryDTO).toList();
            return PageDTO.of(items, query.getPage(), query.getSize(), total);
        });
    }

    private static EntryDTO toEntryDTO(EntryDO e) {
        EntryDTO dto = new EntryDTO();
        dto.setId(e.getId());
        dto.setBizNo(e.getBizNo());
        dto.setAccountId(e.getAccountId());
        dto.setDir(e.getDir());
        dto.setAmount(e.getAmount());
        dto.setBalanceAfter(e.getBalanceAfter());
        dto.setSubject(e.getSubject());
        dto.setRefId(e.getRefId());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setSubjectCode(e.getSubjectCode());
        dto.setBizDate(e.getBizDate());
        // AC-6 additive：币种账套 + 入账时汇率快照 + 本位币折算额（存量行为 null，只读不回填）
        dto.setCurrency(e.getCurrency());
        dto.setExchangeRate(e.getExchangeRate());
        dto.setRateScale(e.getRateScale());
        dto.setBaseAmount(e.getBaseAmount());
        return dto;
    }

    public TransferDTO transfer(String bizNo) {
        TransferDO record = transferMapper.selectByBizNo(bizNo);
        if (record == null) {
            throw new BizException(ResultCode.NOT_FOUND, "转账不存在: " + bizNo);
        }
        TransferDTO dto = new TransferDTO();
        dto.setBizNo(record.getBizNo());
        dto.setLines(TccContextUtil.fromJsonList(record.getLines(), TransferLineDTO.class));
        dto.setSubject(record.getSubject());
        dto.setRefId(record.getRefId());
        dto.setReversed(record.getReversed());
        dto.setVoucherType(record.getVoucherType() == null ? VoucherGuard.NORMAL : record.getVoucherType());
        TransferDO rv = transferMapper.selectReverseByOrig(bizNo);
        dto.setReverseBizNo(rv == null ? null : rv.getBizNo());
        dto.setCreatedAt(record.getCreatedAt());
        dto.setBizDate(record.getBizDate());
        // AC-6 additive：凭证币种与汇率快照（存量凭证为 null）
        dto.setCurrency(record.getCurrency());
        dto.setExchangeRate(record.getExchangeRate());
        dto.setRateScale(record.getRateScale());
        dto.setBaseAmount(record.getBaseAmount());
        return dto;
    }

    /**
     * 红冲蓝补：对原凭证全额反向冲正（差错纠正，区别于业务退款）。
     * 原凭证只读不可篡改；反向凭证 biz_no=RV+seq、ref_id=原 bizNo，借贷互换、余额回滚。
     * (REVERSE,origBizNo) 幂等；冲正凭证不可再冲正；冲正后试算仍平衡。
     */
    public ReverseResultDTO reverse(ReverseRequest request) {
        String origBizNo = request.getOrigBizNo();
        if (origBizNo == null || origBizNo.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "origBizNo 必填");
        }
        if (origBizNo.matches("RV\\d+")) {
            throw new BizException(ResultCode.BIZ_ERROR, "冲正凭证不可再冲正: " + origBizNo);
        }
        return idempotentTemplate.execute("REVERSE", origBizNo, ReverseResultDTO.class, () -> {
            TransferDO orig = transferMapper.selectByBizNo(origBizNo);
            if (orig == null) {
                throw new BizException(ResultCode.NOT_FOUND, "原凭证不存在: " + origBizNo);
            }
            TransferDO existing = transferMapper.selectReverseByOrig(origBizNo);
            if (existing != null) {
                return toReverseResult(existing.getBizNo(), TccContextUtil.fromJsonList(existing.getLines(), TransferLineDTO.class));
            }
            List<TransferLineDTO> origLines = TccContextUtil.fromJsonList(orig.getLines(), TransferLineDTO.class);
            List<TransferLineDTO> reverseLines = new ArrayList<>();
            for (TransferLineDTO l : origLines) {
                reverseLines.add(line(l.getAccountId(), "D".equals(l.getDir()) ? "C" : "D", l.getAmount()));
            }
            String reverseBizNo = "RV" + newEntrySeq();
            String reason = request.getReason() == null || request.getReason().isBlank() ? "差错冲正" : request.getReason();
            // AC-6：冲正沿用原凭证的币种与汇率快照（汇率变动不影响已入账，红冲必须逐值回滚）
            FxTag origFx = orig.getCurrency() == null ? null
                    : new FxTag(orig.getCurrency(), orig.getExchangeRate(), orig.getRateScale());
            post(reverseBizNo, reverseLines, "红冲:" + reason, origBizNo, false, VoucherGuard.REVERSE, null, origFx);
            log.info("红冲 origBizNo={} reverseBizNo={} reason={}", origBizNo, reverseBizNo, reason);
            return toReverseResult(reverseBizNo, reverseLines);
        });
    }

    private static ReverseResultDTO toReverseResult(String reverseBizNo, List<TransferLineDTO> lines) {
        ReverseResultDTO dto = new ReverseResultDTO();
        dto.setReverseBizNo(reverseBizNo);
        dto.setLines(lines);
        return dto;
    }

    /**
     * S-6 差错挂账过账：长短款差额挂 SYS_SUSPENSE，待差错处理（X-1）蓝补核销。
     *
     * <p>长款 D 系统户/C SYS_SUSPENSE，短款 D SYS_SUSPENSE/C 系统户——方向由调用方 lines 决定，
     * 服务只校验（借贷平衡 + 必须涉及 SYS_SUSPENSE）后过账。凭证类型 SUSPENSE、只追加不可篡改；
     * (SUSPENSE,bizNo) 幂等，重复挂账返回原凭证。核销=以新 bizNo 追加反向分录（蓝补），挂账归零。
     */
    public TransferDTO suspensePost(PostRequest request) {
        String bizNo = request.getBizNo();
        if (bizNo == null || bizNo.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "bizNo 必填");
        }
        List<TransferLineDTO> lines = request.getLines();
        validateLines(lines);
        if (lines.stream().noneMatch(l -> SYS_SUSPENSE.equals(l.getAccountId()))) {
            throw new BizException(ResultCode.PARAM_ERROR, "挂账分录必须涉及 " + SYS_SUSPENSE + " 科目");
        }
        String subject = request.getSubject() == null || request.getSubject().isBlank() ? "差错挂账" : request.getSubject();
        return idempotentTemplate.execute("SUSPENSE", bizNo, TransferDTO.class, () -> {
            post(bizNo, lines, subject, request.getRefId(), false, VoucherGuard.SUSPENSE);
            log.info("差错挂账过账 bizNo={} subject={} lines={}", bizNo, subject, lines.size());
            return transfer(bizNo);
        });
    }

    /** S-6 挂账科目余额：未核销差错差额（贷方累计-借方累计，可为负）。 */
    public Long suspenseBalance() {
        return balanceOf(SYS_SUSPENSE);
    }

    /**
     * 试算平衡（既有无参口径<b>逐字节不变</b>）：顶层仍是 CNY 单币种账套的 ΣD/ΣC/Σbalance/户数/笔数。
     * AC-6 additive：{@code byCurrency} 给出每个币种账套的独立平衡，{@code baseCurrency*} 给出
     * 「先按快照汇率折算、再相加」的本位币管理视图——<b>绝不把不同币种金额直接相加</b>。
     */
    public TrialDTO trial() {
        return trial(null);
    }

    /**
     * AC-6 按币种试算。{@code currency=null} → 全账套（顶层 CNY 口径）；非空 → 该账套（顶层即该币种）。
     *
     * <p><b>分片安全</b>：每币种一条<b>顶层 SUM/COUNT + WHERE</b> 查询（与既有 sumDebit()/sumBalance()
     * 同一归并口径），不做非分片键 GROUP BY/DISTINCT；存量 {@code currency IS NULL} 归入 CNY 账套。
     */
    public TrialDTO trial(String currency) {
        String only = currency == null || currency.isBlank() ? null : FxMath.normalize(currency);
        // I-5b：跨片 SUM/COUNT 由 ShardingSphere 归并引擎汇总，并强制走主库（读己之写）
        return readYourWrite(() -> {
            List<String> currencies = only == null ? fxRateService.currencies() : List.of(only);
            List<CurrencyTrialDTO> byCurrency = new ArrayList<>();
            for (String ccy : currencies) {
                byCurrency.add(currencyTrial(ccy));
            }
            CurrencyTrialDTO head = only == null
                    ? byCurrency.stream().filter(x -> BASE_CURRENCY.equals(x.getCurrency())).findFirst()
                            .orElseGet(() -> emptyTrial(BASE_CURRENCY))
                    : byCurrency.get(0);
            TrialDTO dto = new TrialDTO();
            // ---- 既有 7 字段：CNY 单币种口径（单币种现状下与改造前逐值相同） ----
            dto.setTotalDebit(head.getTotalDebit());
            dto.setTotalCredit(head.getTotalCredit());
            dto.setBalanced(head.getTotalDebit() != null && head.getTotalDebit().equals(head.getTotalCredit()));
            dto.setBalanceSum(head.getBalanceSum());
            dto.setAccounts(head.getAccounts());
            // entries 为全账套分录总数（与明细账/全局流水分页同口径，多币种下不重不漏）；
            // 借贷/余额/账户数仍保持 CNY 本位币口径（既有红线逐值不变）。
            dto.setEntries((int) byCurrency.stream()
                    .mapToLong(x -> x.getEntries() == null ? 0L : x.getEntries()).sum());
            dto.setSuspenseBalance(balanceOf(SYS_SUSPENSE));
            // ---- AC-6 additive ----
            dto.setBaseCurrency(BASE_CURRENCY);
            dto.setCurrencies(currencies);
            dto.setByCurrency(byCurrency);
            dto.setMultiCurrencyBalanced(byCurrency.stream()
                    .allMatch(x -> Boolean.TRUE.equals(x.getBalanced()) && Boolean.TRUE.equals(x.getConserved())));
            long baseTotal = 0;
            long baseGross = 0;
            for (CurrencyTrialDTO x : byCurrency) {
                if (x.getBaseCreditTotal() != null) {
                    baseTotal += x.getBaseCreditTotal();
                }
                if (x.getBaseGrossBalance() != null) {
                    baseGross += x.getBaseGrossBalance();
                }
            }
            dto.setBaseCurrencyTotal(baseTotal);
            dto.setBaseCurrencyGrossBalance(baseGross);
            return dto;
        });
    }

    /** 单币种账套试算：本币种内 ΣD=ΣC（balanced）且 Σbalance=0（conserved）。 */
    private CurrencyTrialDTO currencyTrial(String ccy) {
        CurrencyTrialDTO x = new CurrencyTrialDTO();
        x.setCurrency(ccy);
        long debit = nullToZero(entryMapper.sumDebitByCurrency(ccy));
        long credit = nullToZero(entryMapper.sumCreditByCurrency(ccy));
        long balanceSum = nullToZero(balanceMapper.sumBalanceByCurrency(ccy));
        x.setTotalDebit(debit);
        x.setTotalCredit(credit);
        x.setBalanced(debit == credit);
        x.setBalanceSum(balanceSum);
        x.setConserved(balanceSum == 0);
        x.setAccounts(balanceMapper.countByCurrency(ccy));
        x.setEntries(entryMapper.countByCurrency(ccy));
        x.setGrossBalance(nullToZero(balanceMapper.sumAbsBalanceByCurrency(ccy)));
        long[] base = fxRateService.baseRate(ccy);
        if (base == null) {
            // 无可用汇率：本位币视图置 null 且不计入汇总（绝不按 1:1 混算不同币种）
            x.setFxRateToBase(null);
            x.setRateScale(null);
            x.setBaseCreditTotal(null);
            x.setBaseGrossBalance(null);
            return x;
        }
        x.setFxRateToBase(base[0]);
        x.setRateScale((int) base[1]);
        x.setBaseCreditTotal(FxMath.convert(credit, base[0], (int) base[1]));
        x.setBaseGrossBalance(FxMath.convert(x.getGrossBalance(), base[0], (int) base[1]));
        return x;
    }

    private static CurrencyTrialDTO emptyTrial(String ccy) {
        CurrencyTrialDTO x = new CurrencyTrialDTO();
        x.setCurrency(ccy);
        x.setTotalDebit(0L);
        x.setTotalCredit(0L);
        x.setBalanceSum(0L);
        x.setBalanced(true);
        x.setConserved(true);
        x.setAccounts(0);
        x.setEntries(0);
        x.setGrossBalance(0L);
        return x;
    }

    /** TCC Try：按借方行冻结非系统户可用余额。 */
    public void freeze(AccountingTccContext ctx) {
        log.info("账务冻结(TCC Try) bizNo={}", ctx.getBizNo());
        validateLines(ctx.getLines());
        Map<String, AccountStatusDTO> statuses = checkAccounts(ctx.getLines().stream().map(TransferLineDTO::getAccountId).toList());
        // AC-6：一张凭证一个币种账套（混币拒绝）；既有 CNY 单币种路径解析结果恒 CNY，行为不变
        String voucherCurrency = voucherCurrency(ctx.getLines(), statuses);
        // AC-3：受理时锁定会计日快照 —— 跨日切的在途单 Confirm 仍归属受理日，日切不破坏在途 TCC。
        dayClock.snapshot(ctx.getBizNo());
        for (TransferLineDTO line : sorted(ctx.getLines())) {
            if (!"D".equals(line.getDir()) || isSystem(line.getAccountId())) {
                continue;
            }
            BalanceDO balance = lockOrCreate(line.getAccountId(), voucherCurrency);
            long available = balance.getBalance() - balance.getFrozen();
            if (available < line.getAmount()) {
                throw new BizException(ResultCode.BIZ_ERROR, "可用余额不足: " + line.getAccountId());
            }
            balance.setFrozen(balance.getFrozen() + line.getAmount());
            balance.setUpdatedAt(System.currentTimeMillis());
            balanceMapper.update(balance);
            mirror("account_balance", balance.getAccountId());
        }
    }

    /** TCC Confirm：先解冻再过账（过账的可用余额校验不再受本笔冻结影响），同事务。 */
    public void postAndUnfreeze(AccountingTccContext ctx) {
        log.info("账务过账(TCC Confirm) bizNo={} reversed={}", ctx.getBizNo(), ctx.getReversed());
        unfreeze(ctx);
        post(ctx.getBizNo(), ctx.getLines(), ctx.getSubject(), ctx.getRefId(), Boolean.TRUE.equals(ctx.getReversed()));
    }

    /** TCC Cancel：仅解冻。 */
    public void unfreeze(AccountingTccContext ctx) {
        log.info("账务解冻(TCC Cancel) bizNo={}", ctx.getBizNo());
        for (TransferLineDTO line : sorted(ctx.getLines())) {
            if (!"D".equals(line.getDir()) || isSystem(line.getAccountId())) {
                continue;
            }
            BalanceDO balance = balanceMapper.selectForUpdate(line.getAccountId());
            if (balance == null) {
                continue;
            }
            balance.setFrozen(Math.max(0, balance.getFrozen() - line.getAmount()));
            balance.setUpdatedAt(System.currentTimeMillis());
            balanceMapper.update(balance);
            mirror("account_balance", balance.getAccountId());
        }
    }

    /** 复式记账核心（普通/冲正凭证）：凭证类型按 bizNo 约定推断（RV+seq=REVERSE，其余 NORMAL）。 */
    public void post(String bizNo, List<TransferLineDTO> lines, String subject, String refId, boolean reversed) {
        post(bizNo, lines, subject, refId, reversed, VoucherGuard.inferType(bizNo));
    }

    /**
     * 复式记账核心：校验借贷平衡 → 排序加锁 → 逐行过账 → 落转账凭证（只追加）。
     *
     * <p>S-6 VoucherGuard：凭证落库后 lines 只读——同 bizNo 重放且 lines 一致视为幂等，
     * 直接返回不重复过账；lines/类型不一致拒绝。任何路径不得 UPDATE 原凭证。
     */
    public void post(String bizNo, List<TransferLineDTO> lines, String subject, String refId, boolean reversed, String voucherType) {
        post(bizNo, lines, subject, refId, reversed, voucherType, null);
    }

    /**
     * AC-3 会计日显式归属重载（仅日终损益结转使用：结转凭证必须归属被结转的会计日，
     * 即使该日已 CLOSED）。{@code bizDateOverride=null} 时与改造前完全一致——
     * 由 {@link AccountingDayClock#dayFor(String)} 解析（在途 TCC 用受理快照，其余用当前 OPEN 日）。
     *
     * <p><b>口径不变</b>：新增的只是分录/凭证的 subject_code 与 biz_date 两个标签列，
     * 余额计算、借贷方向、加锁顺序、幂等键与 VoucherGuard 护栏逐字未改。
     */
    public void post(String bizNo, List<TransferLineDTO> lines, String subject, String refId, boolean reversed,
                     String voucherType, Long bizDateOverride) {
        post(bizNo, lines, subject, refId, reversed, voucherType, bizDateOverride, null);
    }

    /**
     * AC-6 多币种重载：{@code fx=null} 时与改造前<b>逐字节一致</b>（币种由账户解析，既有账户全 CNY，
     * 本位币折算率恒 1:1，零额外汇率查询）。{@code fx} 非空时按给定币种账套 + 汇率快照落标签列。
     *
     * <p><b>口径不变</b>：新增的只是 currency/exchange_rate/rate_scale/base_amount 四个标签列；
     * 余额计算（balance=credit−debit）、借贷方向、排序加锁、幂等键与 VoucherGuard 护栏逐字未改。
     * <b>一张凭证只能一个币种</b>：混币 lines 一律拒绝，跨币种必须经 exchange 拆成两张同币种凭证。
     */
    public void post(String bizNo, List<TransferLineDTO> lines, String subject, String refId, boolean reversed,
                     String voucherType, Long bizDateOverride, FxTag fx) {
        validateLines(lines);
        Map<String, AccountStatusDTO> statuses = checkAccounts(lines.stream().map(TransferLineDTO::getAccountId).toList());
        // AC-6：无论是否显式指定币种，都按账户真值解析并强制「一张凭证一个币种」——混币一律拒绝
        String resolved = voucherCurrency(lines, statuses);
        String currency = fx != null && fx.currency() != null && !fx.currency().isBlank()
                ? FxMath.normalize(fx.currency()) : resolved;
        if (!currency.equals(resolved)) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "凭证币种与分录账户币种不符（禁止混币记账）: " + currency + " vs " + resolved);
        }
        // AC-6 汇率快照：入账时锁定，后续汇率变动不影响已入账（本位币 CNY 恒 1:1）
        long rate;
        int scale;
        if (fx != null && fx.exchangeRate() != null) {
            rate = fx.exchangeRate();
            scale = fx.rateScale() == null || fx.rateScale() <= 0 ? FxMath.DEFAULT_SCALE : fx.rateScale();
        } else if (BASE_CURRENCY.equals(currency)) {
            rate = FxMath.RATE_ONE;
            scale = FxMath.DEFAULT_SCALE;
        } else {
            long[] base = fxRateService.baseRate(currency);
            if (base == null) {
                throw new BizException(ResultCode.BIZ_ERROR, "缺少本位币折算汇率，禁止入账: " + currency);
            }
            rate = base[0];
            scale = (int) base[1];
        }
        if (!BASE_CURRENCY.equals(currency)) {
            fxRateService.registerCurrency(currency);
        }
        String linesJson = TccContextUtil.toJson(lines);
        TransferDO existing = voucherGuard.checkAppend(bizNo, linesJson, voucherType);
        if (existing != null) {
            log.info("凭证已存在且 lines 一致（幂等，跳过重复过账） bizNo={}", bizNo);
            return;
        }
        long now = System.currentTimeMillis();
        long bizDate = bizDateOverride != null ? bizDateOverride : dayClock.dayFor(bizNo);
        long voucherDebit = 0;
        for (TransferLineDTO line : sorted(lines)) {
            BalanceDO balance = lockOrCreate(line.getAccountId(), currency);
            long amount = line.getAmount();
            long newBalance;
            if ("D".equals(line.getDir())) {
                long available = balance.getBalance() - balance.getFrozen();
                if (!isSystem(line.getAccountId()) && available < amount) {
                    throw new BizException(ResultCode.BIZ_ERROR, "可用余额不足: " + line.getAccountId());
                }
                newBalance = balance.getBalance() - amount;
                voucherDebit += amount;
            } else {
                newBalance = balance.getBalance() + amount;
            }
            balance.setBalance(newBalance);
            balance.setUpdatedAt(now);
            balanceMapper.update(balance);
            mirror("account_balance", balance.getAccountId());

            long seq = newEntrySeq();
            EntryDO entry = new EntryDO();
            entry.setId("E" + seq);
            entry.setSeq(seq);
            entry.setBizNo(bizNo);
            entry.setAccountId(line.getAccountId());
            entry.setDir(line.getDir());
            entry.setAmount(amount);
            entry.setBalanceAfter(newBalance);
            entry.setSubject(subject);
            entry.setRefId(refId);
            entry.setCreatedAt(now);
            // AC-2/AC-3：科目 + 会计日标签（纯元数据，不影响上面的余额计算）
            entry.setSubjectCode(subjectService.subjectOf(line.getAccountId()));
            entry.setBizDate(bizDate);
            // AC-6：币种账套 + 汇率快照 + 本位币折算额（同为纯标签列，绝不参与余额/方向计算）
            entry.setCurrency(currency);
            entry.setExchangeRate(rate);
            entry.setRateScale(scale);
            entry.setBaseAmount(FxMath.convert(amount, rate, scale));
            entryMapper.insert(entry);
            mirror("ledger_entry", entry.getId());
        }
        TransferDO record = new TransferDO();
        record.setBizNo(bizNo);
        record.setLines(linesJson);
        record.setSubject(subject);
        record.setRefId(refId);
        record.setReversed(reversed);
        record.setVoucherType(voucherType);
        record.setCreatedAt(now);
        record.setBizDate(bizDate);
        record.setCurrency(currency);
        record.setExchangeRate(rate);
        record.setRateScale(scale);
        record.setBaseAmount(FxMath.convert(voucherDebit, rate, scale));
        try {
            transferMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发追加竞争：胜者凭证必须与本次 lines/类型一致（不可篡改），否则拒绝
            voucherGuard.assertUnchanged(transferMapper.selectByBizNo(bizNo), linesJson, voucherType);
            log.info("转账记录已存在（幂等） bizNo={}", bizNo);
        }
    }

    /**
     * AC-6 凭证币种解析：系统户按命名约定（SYS_CASH=CNY / SYS_CASH_USD=USD），
     * 客户账户按账户域币种属性（存量缺省 CNY）。<b>混币凭证一律拒绝</b>——
     * 不同币种金额不得直接相加，跨币种必须经 exchange 拆成两张同币种凭证。
     */
    private String voucherCurrency(List<TransferLineDTO> lines, Map<String, AccountStatusDTO> statuses) {
        String resolved = null;
        for (TransferLineDTO line : lines) {
            String ccy = lineCurrency(line.getAccountId(), statuses);
            if (resolved == null) {
                resolved = ccy;
            } else if (!resolved.equals(ccy)) {
                throw new BizException(ResultCode.PARAM_ERROR,
                        "跨币种凭证禁止混币记账（须拆成同币种凭证经 exchange）: " + resolved + " / " + ccy);
            }
        }
        return resolved == null ? BASE_CURRENCY : resolved;
    }

    private String lineCurrency(String accountId, Map<String, AccountStatusDTO> statuses) {
        if (isSystem(accountId)) {
            return AccountSubjectMapping.currencyOfSystemAccount(accountId);
        }
        AccountStatusDTO status = statuses == null ? null : statuses.get(accountId);
        String ccy = status == null ? null : status.getCurrency();
        return ccy == null || ccy.isBlank() ? BASE_CURRENCY : ccy.trim().toUpperCase(Locale.ROOT);
    }

    private void validateLines(List<TransferLineDTO> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "分录行不能为空");
        }
        long debit = 0;
        long credit = 0;
        for (TransferLineDTO line : lines) {
            if (line.getAccountId() == null || line.getAccountId().isBlank()) {
                throw new BizException(ResultCode.PARAM_ERROR, "分录行账户必填");
            }
            if (!"D".equals(line.getDir()) && !"C".equals(line.getDir())) {
                throw new BizException(ResultCode.PARAM_ERROR, "dir 仅支持 D 或 C");
            }
            MoneyUtil.requireValid(line.getAmount());
            if ("D".equals(line.getDir())) {
                debit += line.getAmount();
            } else {
                credit += line.getAmount();
            }
        }
        if (debit != credit) {
            throw new BizException(ResultCode.PARAM_ERROR, "借贷不平衡: D=" + debit + " C=" + credit);
        }
    }

    /** 记账前账户校验（口径不变）；AC-6 起返回状态快照供币种解析复用，不增加额外 RPC。 */
    private Map<String, AccountStatusDTO> checkAccounts(List<String> accountIds) {
        List<String> real = accountIds.stream().filter(id -> !isSystem(id)).distinct().toList();
        if (real.isEmpty()) {
            return Map.of();
        }
        Map<String, AccountStatusDTO> statuses = accountFacade.check(real).orThrow();
        for (String id : real) {
            AccountStatusDTO status = statuses.get(id);
            if (status == null) {
                throw new BizException(ResultCode.NOT_FOUND, "账户不存在: " + id);
            }
            if (!"ACTIVE".equals(status.getStatus())) {
                throw new BizException(ResultCode.BIZ_ERROR, "账户已冻结: " + id);
            }
        }
        return statuses;
    }

    private BalanceDO lockOrCreate(String accountId) {
        return lockOrCreate(accountId, BASE_CURRENCY);
    }

    /** AC-6：余额行币种标签（additive 列）；既有行不回填、不 UPDATE 币种，存量 NULL 按 CNY 读。 */
    private BalanceDO lockOrCreate(String accountId, String currency) {
        BalanceDO balance = balanceMapper.selectForUpdate(accountId);
        if (balance != null) {
            return balance;
        }
        BalanceDO fresh = new BalanceDO();
        fresh.setAccountId(accountId);
        fresh.setBalance(0L);
        fresh.setFrozen(0L);
        fresh.setVersion(0);
        fresh.setUpdatedAt(System.currentTimeMillis());
        fresh.setCurrency(currency);
        try {
            balanceMapper.insert(fresh);
            mirror("account_balance", accountId);
        } catch (DuplicateKeyException ignored) {
            // 并发开户竞争：对手已插入，直接加锁读取
        }
        return balanceMapper.selectForUpdate(accountId);
    }

    private long balanceOf(String accountId) {
        BalanceDO balance = readYourWrite(() -> balanceMapper.select(accountId));
        return balance == null ? 0L : balance.getBalance();
    }

    // ------------------------------------------------------------------ I-5b 分片支撑

    /**
     * I-5b 分录取号：分片态走全局发号（雪花，杜绝各片 sequences 重号 E-7）；
     * 单库态（local / 一键回滚后）仍走 {@code next_value_for('seq_entry')}。
     * <b>ID 形态不变</b>：分录 id 仍 {@code E+十进制数字}、冲正凭证号仍 {@code RV+十进制数字}
     * （{@code RV\d+} 判定与 VoucherGuard 类型推断照旧生效），seq 仍为 BIGINT 且全局唯一递增。
     */
    private long newEntrySeq() {
        ShardRuntime runtime = shardRuntime.getIfAvailable();
        if (runtime != null && runtime.isActive()) {
            return idGenerator.getObject().nextLong();
        }
        return entryMapper.nextId();
    }

    /** I-5b 全行双写：余额/冻结是可变行，必须整行真值同步影子侧，回滚后才能逐条一致。 */
    private void mirror(String table, Object pk) {
        ShardRowMirror mirror = shardRowMirror.getIfAvailable();
        if (mirror != null) {
            mirror.mirror(table, pk);
        }
    }

    /** I-5b 资金读己之写：强制走主库（未装配分片时零开销直接执行）。 */
    private <T> T readYourWrite(Supplier<T> action) {
        ShardRuntime runtime = shardRuntime.getIfAvailable();
        return runtime == null ? action.get() : runtime.readYourWrite(action);
    }

    private static List<TransferLineDTO> sorted(List<TransferLineDTO> lines) {
        List<TransferLineDTO> copy = new ArrayList<>(lines);
        copy.sort(Comparator.comparing(TransferLineDTO::getAccountId));
        return copy;
    }

    private static boolean isSystem(String accountId) {
        return accountId != null && accountId.startsWith("SYS_");
    }

    /** I-5b：跨片 SUM 顶层投影在空表时返回 NULL，统一归零（与单库 COALESCE 口径逐值一致）。 */
    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private static TransferLineDTO line(String accountId, String dir, long amount) {
        TransferLineDTO line = new TransferLineDTO();
        line.setAccountId(accountId);
        line.setDir(dir);
        line.setAmount(amount);
        return line;
    }
}