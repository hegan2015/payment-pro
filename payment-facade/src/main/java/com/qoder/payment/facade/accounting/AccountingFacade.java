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

    /** 红冲蓝补：对原凭证全额反向冲正（差错纠正）。(REVERSE,origBizNo) 幂等，原凭证不可篡改。 */
    Result<ReverseResultDTO> reverse(ReverseRequest request);

    /**
     * S-6 差错挂账：长短款差额挂 SYS_SUSPENSE（长款 D 系统户/C SYS_SUSPENSE，短款反之，由 lines 决定）。
     * (SUSPENSE,bizNo) 幂等；凭证只追加不可篡改；核销=新 bizNo 追加反向分录（蓝补）。
     */
    Result<TransferDTO> suspensePost(PostRequest request);

    /** S-6 挂账科目 SYS_SUSPENSE 余额（未核销差错差额，可为负）。 */
    Result<Long> suspenseBalance();

    Result<TrialDTO> trial();

    /**
     * S-7 备付金账实核对：SYS_CASH 账务余额 vs 模拟银行存管实际余额（P0 雏形）。
     * 差异→ERROR 告警日志 + payment_accounting_reserve_diff_total 指标 + reserve_check_log 留痕。
     * 差异→差错单（X-1 DisputeFacade）接线由集成期完成，本方法只暴露核对与告警。
     */
    Result<ReserveCheckDTO> reserveCheck();

    /** S-7 备付金核对留痕（最近 limit 条，倒序）。 */
    Result<List<ReserveCheckDTO>> reserveLogs(int limit);

    /** S-7 模拟银行存管侧调整（只影响实际余额侧，不动账务），返回调整后即时核对结果。 */
    Result<ReserveCheckDTO> reserveAdjust(ReserveAdjustRequest request);

    // ==================================================================
    // P1 第二期 · E 泳道账务工作流（AC-2/AC-3/AC-4/AC-5）——以下全部 additive，
    // 既有方法签名/语义/幂等键一字未改。
    // ==================================================================

    // ---------------- AC-2 会计科目体系 ----------------

    /** 科目目录（资产/负债/共同/权益/损益，含层级与系统户固定映射）。 */
    Result<List<SubjectDTO>> listSubjects();

    /** 科目余额表：bizDate 为空取最近已汇总日；subjectCode 为空取全部科目。 */
    Result<List<SubjectBalanceDTO>> listSubjectBalance(String subjectCode, Long bizDate);

    /** 单科目某会计日余额（无汇总行时按账户余额实时归集，只读）。 */
    Result<SubjectBalanceDTO> getSubjectBalance(String subjectCode, Long bizDate);

    /** 账户→科目映射（系统户固定映射不可改；客户账户默认 2001 客户余额）。 */
    Result<SubjectDTO> mapAccountSubject(String accountId, String subjectCode);

    /** 账户当前生效科目代码（只读）。 */
    Result<String> subjectOfAccount(String accountId);

    // ---------------- AC-3 日切 + 日终批处理 ----------------

    /** 当前会计日（OPEN 日，epoch millis 当日 00:00 Asia/Shanghai）。 */
    Result<AccountingDayDTO> currentAccountingDay();

    /** 会计日列表（倒序，最近 limit 条）。 */
    Result<List<AccountingDayDTO>> listAccountingDays(int limit);

    /**
     * 日切：OPEN→CUTTING→快照→科目汇总/日终报告→CLOSED→次日 OPEN。
     * 幂等 (DAY_CUT,bizDate) + CAS + ShedLock（多实例不重复）；只切标签不动余额。
     * bizDate 为空 = 当前 OPEN 日。shardTotal&gt;1 时科目汇总按科目取模分片（不重不漏）。
     */
    Result<AccountingDayDTO> runDayCut(Long bizDate, int shardIndex, int shardTotal);

    /** 日终批（科目汇总 + 损益报告 + 可选结转）；失败可重跑，幂等。 */
    Result<DayEndReportDTO> runDayEnd(Long bizDate, int shardIndex, int shardTotal);

    /** 日终报告（只读，不触发批处理）。 */
    Result<DayEndReportDTO> getDayEndReport(Long bizDate);

    // ---------------- AC-4 账簿/凭证查询（只读） ----------------

    /** 总账：科目 + 会计日汇总（分页）。 */
    Result<PageDTO<GeneralLedgerDTO>> generalLedger(String subjectCode, Long bizDate, int page, int size);

    /** 明细账：逐笔分录（跨片分页归并，不重不漏）。 */
    Result<PageDTO<EntryDTO>> detailLedger(LedgerQuery query);

    /** 科目余额表（期末余额，含类别根汇总行）。 */
    Result<List<SubjectBalanceDTO>> balanceSheet(Long bizDate);

    /** 凭证联查（bizNo/refId/科目/账户/日期区间，分页；凭证只读不可改）。 */
    Result<PageDTO<VoucherDTO>> vouchers(VoucherQuery query);

    /** 单凭证联查：凭证头 + 分录 + 红冲关联。 */
    Result<VoucherDTO> voucher(String bizNo);

    // ---------------- AC-5 账实核对 ----------------

    /**
     * 账实核对（按会计日 + 多渠道）：SYS_CASH 账面 vs 银行存管/渠道账单实际。
     * 差异分级告警 + 留痕 + 差错单（幂等 by bizDate:channel）。channels 为空取配置默认。
     */
    Result<ReserveReconDTO> reserveRecon(Long bizDate, List<String> channels);

    /** AC-5 核对留痕（按会计日/渠道筛选，倒序）。 */
    Result<List<ReserveCheckDTO>> reserveReconLogs(Long bizDate, String channelCode, int limit);

    // ==================================================================
    // P2 第三期 · C 泳道（A-4 多币种+汇率 / AC-6 多币种账务）——以下全部 additive，
    // 既有方法签名/语义/幂等键一字未改；trial() 顶层仍是 CNY 单币种口径。
    // ==================================================================

    // ---------------- A-4 汇率 ----------------

    /** 当前生效汇率（effectiveAt ≤ now 的最新版本）；version 非空则取指定版本。无则 NOT_FOUND。 */
    Result<FxRateDTO> getFxRate(String fromCurrency, String toCurrency, Long version);

    /** 汇率版本历史（倒序，最近 limit 条；币对可选）。 */
    Result<List<FxRateDTO>> listFxRates(String fromCurrency, String toCurrency, int limit);

    /** 发布汇率版本（买入/卖出/中间价 + 生效时间 + 来源）；同参数重复发布幂等返回既有版本。 */
    Result<FxRateDTO> setFxRate(FxRateSetRequest request);

    /** 币种账套目录（CNY 恒在首位）。 */
    Result<List<String>> listCurrencies();

    // ---------------- A-4 换汇 ----------------

    /**
     * 换汇：fromAccount（卖出币种）→ toAccount（买入币种），(EXCHANGE,bizNo) 幂等。
     * 汇率快照落库；生成两张<b>同币种</b>凭证（bizNo-F / bizNo-T）+ 汇兑损益 SYS_FX，各账套内 D=C 且 Σbalance=0。
     */
    Result<ExchangeResultDTO> exchange(ExchangeRequest request);

    /** 换汇单查询（含汇率快照与两张凭证号）。 */
    Result<ExchangeResultDTO> fxExchange(String bizNo);

    // ---------------- AC-6 多币种试算 ----------------

    /**
     * 按币种试算：currency 为空 = 全账套（顶层仍 CNY 口径 + byCurrency 明细）；
     * 非空 = 该币种账套（顶层即该币种 totals）。既有无参 {@link #trial()} 语义与返回结构不变。
     */
    Result<TrialDTO> trial(String currency);
}