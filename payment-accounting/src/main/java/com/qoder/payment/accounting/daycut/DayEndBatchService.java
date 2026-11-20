package com.qoder.payment.accounting.daycut;

import com.qoder.payment.accounting.dataobject.AccountDayTotalDO;
import com.qoder.payment.accounting.dataobject.BalanceDO;
import com.qoder.payment.accounting.dataobject.DayEndReportDO;
import com.qoder.payment.accounting.dataobject.SubjectBalanceDO;
import com.qoder.payment.accounting.dataobject.SubjectDO;
import com.qoder.payment.accounting.mapper.BalanceMapper;
import com.qoder.payment.accounting.mapper.DayEndReportMapper;
import com.qoder.payment.accounting.mapper.EntryMapper;
import com.qoder.payment.accounting.mapper.SubjectBalanceMapper;
import com.qoder.payment.accounting.service.AccountingService;
import com.qoder.payment.accounting.subject.AccountSubjectMapping;
import com.qoder.payment.accounting.subject.SubjectService;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.db.ShardKeys;
import com.qoder.payment.common.db.ShardRuntime;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.tcc.IdempotentTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * AC-3 日终批处理：科目汇总（总账/明细账结转）+ 损益报告 + 可选损益结转。
 *
 * <p><b>分片不重不漏</b>：科目汇总按 {@code ShardKeys.shardOf(subjectCode, shardTotal)} 取模分片，
 * 各片处理互不相交的科目集合；同一科目只可能被一个片写入 ⇒ 不重；所有片跑完覆盖全部科目 ⇒ 不漏。
 * 同片多实例由 ShedLock（{@code accountingDayEnd:<day>:<i>/<n>}）互斥。
 *
 * <p><b>跨片聚合正确性</b>：发生额来自 {@code GROUP BY account_id}（分片键 ⇒ 分组不跨片）+
 * 顶层 SUM 投影；期末余额来自 {@code account_balance} 全行读取后 Java 侧按科目归集。
 * 绝不把 SUM 藏进 COALESCE 等函数（I-5a/b 已踩坑：会被当普通投影返回每片一行）。
 *
 * <p><b>幂等可重跑</b>：subject_balance / day_end_report 都是派生行，存在即 UPDATE、不存在即 INSERT，
 * 绝不 DELETE；重复跑批结果逐值一致。损益结转经 {@code (DAY_CUT,bizDate)} 幂等键 + 凭证 bizNo 幂等，
 * 只会产生一张结转凭证。
 *
 * <p><b>结转默认关闭</b>（{@code accounting.dayend.profit-transfer.enabled=false}）：
 * 既有断言依赖 SYS_FEE 余额（手续费闭环），共享栈不得自动清零；显式打开后
 * D 损益户 / C SYS_PROFIT 复式结转，结转后损益户归零且试算仍 D=C、Σbalance=0。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DayEndBatchService {

    public static final String SYS_PROFIT = "SYS_PROFIT";
    public static final String KEY_PROFIT_TRANSFER = "accounting.dayend.profit-transfer.enabled";
    public static final String BATCH_DAY_END = "DAY_END";
    public static final String BATCH_REPORT = "DAY_REPORT";
    private static final Duration LOCK_AT_MOST = Duration.ofMinutes(10);

    private final EntryMapper entryMapper;
    private final BalanceMapper balanceMapper;
    private final SubjectBalanceMapper subjectBalanceMapper;
    private final DayEndReportMapper reportMapper;
    private final SubjectService subjectService;
    private final AccountingService accountingService;
    private final IdempotentTemplate idempotentTemplate;
    private final DynamicConfig dynamicConfig;
    private final DayCutLock dayCutLock;
    private final ObjectProvider<ShardRuntime> shardRuntime;

    // ------------------------------------------------------------------ 纯计算（只读）

    /**
     * 计算某会计日全部明细科目（level=2）的发生额与期末余额（只读，不写库）。
     * 日终批与「未跑批时的实时余额表」共用同一口径 ⇒ 总账 = 明细账汇总恒等。
     */
    public Map<String, SubjectBalanceDO> compute(long bizDate) {
        Map<String, SubjectDO> catalog = subjectService.catalogSnapshot();
        List<AccountDayTotalDO> totals = readYourWrite(() -> entryMapper.sumByAccountForDay(bizDate));
        List<BalanceDO> balances = readYourWrite(balanceMapper::selectAll);

        Map<String, SubjectBalanceDO> out = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (SubjectDO subject : catalog.values()) {
            if (subject.getSubjectLevel() == null || subject.getSubjectLevel() != 2) {
                continue;
            }
            SubjectBalanceDO row = new SubjectBalanceDO();
            row.setSubjectCode(subject.getSubjectCode());
            row.setBizDate(bizDate);
            row.setDebitTotal(0L);
            row.setCreditTotal(0L);
            row.setBalance(0L);
            row.setOpeningBalance(0L);
            row.setEntryCount(0);
            row.setAccountCount(0);
            row.setUpdatedAt(now);
            out.put(subject.getSubjectCode(), row);
        }
        // 期末余额：account_balance 全行（跨片全表扫描返回所有行，无聚合归并歧义）按科目归集
        for (BalanceDO balance : balances) {
            String code = subjectService.subjectOf(balance.getAccountId());
            SubjectBalanceDO row = out.get(code);
            if (row == null) {
                continue;
            }
            row.setBalance(row.getBalance() + AccountingDayClock.nz(balance.getBalance()));
            row.setAccountCount(row.getAccountCount() + 1);
        }
        // 当日发生额：GROUP BY 分片键 account_id，每组完整落单片
        for (AccountDayTotalDO total : totals) {
            String code = subjectService.subjectOf(total.getAccountId());
            SubjectBalanceDO row = out.get(code);
            if (row == null) {
                continue;
            }
            row.setDebitTotal(row.getDebitTotal() + AccountingDayClock.nz(total.getDebitTotal()));
            row.setCreditTotal(row.getCreditTotal() + AccountingDayClock.nz(total.getCreditTotal()));
            row.setEntryCount(row.getEntryCount() + (total.getEntryCount() == null ? 0 : total.getEntryCount()));
        }
        for (SubjectBalanceDO row : out.values()) {
            row.setOpeningBalance(row.getBalance() - (row.getCreditTotal() - row.getDebitTotal()));
        }
        return out;
    }

    /** 余额校验和（sorted accountId=balance 的 SHA-256）：日切前后逐账户连续性证据。 */
    public String balanceChecksum() {
        List<BalanceDO> balances = readYourWrite(balanceMapper::selectAll);
        TreeMap<String, Long> sorted = new TreeMap<>();
        for (BalanceDO b : balances) {
            sorted.put(b.getAccountId(), AccountingDayClock.nz(b.getBalance()));
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : sorted.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return sha256(sb.toString());
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ------------------------------------------------------------------ 科目汇总（分片写）

    /** 科目汇总：只写本片负责的科目（取模分片，不重不漏），派生行幂等 upsert。 */
    public int aggregate(long bizDate, int shardIndex, int shardTotal) {
        int total = Math.max(1, shardTotal);
        int index = Math.max(0, Math.min(shardIndex, total - 1));
        Map<String, SubjectBalanceDO> computed = compute(bizDate);
        int written = 0;
        for (SubjectBalanceDO row : computed.values()) {
            if (ShardKeys.shardOf(row.getSubjectCode(), total) != index) {
                continue;
            }
            upsertSubjectBalance(row);
            written++;
        }
        log.info("AC-3 科目汇总 bizDate={} shard={}/{} subjects={} written={} debit={} credit={} balanceSum={}",
                AccountingDayClock.dayText(bizDate), index, total, computed.size(), written,
                sum(computed, SubjectBalanceDO::getDebitTotal), sum(computed, SubjectBalanceDO::getCreditTotal),
                sum(computed, SubjectBalanceDO::getBalance));
        return written;
    }

    private void upsertSubjectBalance(SubjectBalanceDO row) {
        row.setUpdatedAt(System.currentTimeMillis());
        if (subjectBalanceMapper.select(row.getSubjectCode(), row.getBizDate()) == null) {
            try {
                subjectBalanceMapper.insert(row);
                return;
            } catch (DuplicateKeyException ignored) {
                // 并发跑批竞争：转为 UPDATE 派生值
            }
        }
        subjectBalanceMapper.update(row);
    }

    private static long sum(Map<String, SubjectBalanceDO> rows, java.util.function.Function<SubjectBalanceDO, Long> f) {
        long total = 0;
        for (SubjectBalanceDO row : rows.values()) {
            total += AccountingDayClock.nz(f.apply(row));
        }
        return total;
    }

    // ------------------------------------------------------------------ 日终报告 + 损益结转

    /**
     * 日终报告（全局锁 {@code accountingDayReport:<day>}，多实例只有一个重算）：
     * 从 subject_balance 派生损益汇总；若显式开启结转则复式过账后重算科目汇总与报告。
     * 每个分片跑完都会调用 ⇒ 最后一片产出完整报告；重跑幂等。
     */
    public DayEndReportDO finishReport(long bizDate) {
        return dayCutLock.withLock("accountingDayReport:" + bizDate, LOCK_AT_MOST,
                () -> doFinishReport(bizDate),
                () -> {
                    DayEndReportDO existing = reportMapper.select(bizDate);
                    log.info("AC-3 日终报告锁未获取（其它实例正在重算）bizDate={} existing={}",
                            AccountingDayClock.dayText(bizDate), existing != null);
                    return existing;
                });
    }

    private DayEndReportDO doFinishReport(long bizDate) {
        boolean transferEnabled = dynamicConfig.getBool(KEY_PROFIT_TRANSFER, false);
        DayEndReportDO previous = reportMapper.select(bizDate);
        boolean alreadyTransferred = previous != null && Boolean.TRUE.equals(previous.getTransferred());
        String transferBizNo = previous == null ? null : previous.getTransferBizNo();

        if (transferEnabled && !alreadyTransferred) {
            transferBizNo = transferProfit(bizDate);
            // 结转动账后科目余额已变，重算全量科目汇总（单片全量，派生行幂等）
            aggregate(bizDate, 0, 1);
            alreadyTransferred = transferBizNo != null;
        }

        List<SubjectBalanceDO> rows = subjectBalanceMapper.selectByDate(bizDate);
        Map<String, SubjectDO> catalog = subjectService.catalogSnapshot();
        long income = 0;
        long expense = 0;
        long debit = 0;
        long credit = 0;
        long balanceSum = 0;
        int entries = 0;
        for (SubjectBalanceDO row : rows) {
            SubjectDO subject = catalog.get(row.getSubjectCode());
            long d = AccountingDayClock.nz(row.getDebitTotal());
            long c = AccountingDayClock.nz(row.getCreditTotal());
            debit += d;
            credit += c;
            balanceSum += AccountingDayClock.nz(row.getBalance());
            entries += row.getEntryCount() == null ? 0 : row.getEntryCount();
            if (subject == null) {
                continue;
            }
            if (AccountSubjectMapping.TYPE_INCOME.equals(subject.getType())) {
                income += c - d;
            } else if (AccountSubjectMapping.TYPE_EXPENSE.equals(subject.getType())) {
                expense += d - c;
            }
        }
        DayEndReportDO report = new DayEndReportDO();
        report.setBizDate(bizDate);
        report.setIncomeTotal(income);
        report.setExpenseTotal(expense);
        report.setProfit(income - expense);
        report.setTransferred(alreadyTransferred);
        report.setTransferBizNo(transferBizNo);
        report.setDebitTotal(debit);
        report.setCreditTotal(credit);
        report.setBalanceSum(balanceSum);
        report.setSubjectCount(rows.size());
        report.setEntryCount(entries);
        report.setLegacyEntryCount(readYourWrite(entryMapper::countLegacy));
        report.setStatus(debit == credit && balanceSum == 0 ? "SUCCESS" : "FAILED");
        report.setUpdatedAt(System.currentTimeMillis());
        if (previous == null) {
            try {
                reportMapper.insert(report);
            } catch (DuplicateKeyException e) {
                reportMapper.update(report);
            }
        } else {
            reportMapper.update(report);
        }
        log.info("AC-3 日终报告 bizDate={} income={} expense={} profit={} D={} C={} balanceSum={} "
                        + "transferred={} status={}", AccountingDayClock.dayText(bizDate), income, expense,
                income - expense, debit, credit, balanceSum, alreadyTransferred, report.getStatus());
        return report;
    }

    /**
     * 损益结转（复式记账，幂等键 {@code (DAY_CUT,bizDate)}，凭证号 {@code DAYCUT<bizDate>}）：
     * 收入户余额&gt;0 → D 收入户 / C SYS_PROFIT；费用户余额&lt;0 → D SYS_PROFIT / C 费用户。
     * 结转后损益户余额归零，SYS_PROFIT 承接利润，试算仍 D=C 且 Σbalance=0。
     * 凭证会计日显式归属被结转日（即使该日已 CLOSED）。
     */
    private String transferProfit(long bizDate) {
        String bizNo = "DAYCUT" + bizDate;
        Map<String, SubjectDO> catalog = subjectService.catalogSnapshot();
        List<BalanceDO> balances = readYourWrite(balanceMapper::selectAll);
        Map<String, Long> byAccount = new LinkedHashMap<>();
        for (BalanceDO b : balances) {
            byAccount.put(b.getAccountId(), AccountingDayClock.nz(b.getBalance()));
        }
        List<TransferLineDTO> lines = new ArrayList<>();
        for (SubjectDO subject : catalog.values()) {
            if (subject.getSubjectLevel() == null || subject.getSubjectLevel() != 2
                    || !AccountSubjectMapping.isProfitLoss(subject.getType())
                    || subject.getSystemAccount() == null) {
                continue;
            }
            long balance = byAccount.getOrDefault(subject.getSystemAccount(), 0L);
            if (balance == 0) {
                continue;
            }
            if (balance > 0) {
                lines.add(line(subject.getSystemAccount(), "D", balance));
                lines.add(line(SYS_PROFIT, "C", balance));
            } else {
                lines.add(line(SYS_PROFIT, "D", -balance));
                lines.add(line(subject.getSystemAccount(), "C", -balance));
            }
        }
        if (lines.isEmpty()) {
            log.info("AC-3 损益结转跳过（损益户余额全 0）bizDate={}", AccountingDayClock.dayText(bizDate));
            return null;
        }
        idempotentTemplate.execute("DAY_CUT", String.valueOf(bizDate), Void.class, () -> {
            accountingService.post(bizNo, lines, "日终损益结转", null, false,
                    com.qoder.payment.accounting.service.VoucherGuard.NORMAL, bizDate);
            return null;
        });
        log.info("AC-3 损益结转完成 bizDate={} voucher={} lines={}", AccountingDayClock.dayText(bizDate), bizNo, lines.size());
        return bizNo;
    }

    private static TransferLineDTO line(String accountId, String dir, long amount) {
        TransferLineDTO line = new TransferLineDTO();
        line.setAccountId(accountId);
        line.setDir(dir);
        line.setAmount(amount);
        return line;
    }

    public DayEndReportDO report(long bizDate) {
        return reportMapper.select(bizDate);
    }

    public boolean profitTransferEnabled() {
        return dynamicConfig.getBool(KEY_PROFIT_TRANSFER, false);
    }

    private <T> T readYourWrite(Supplier<T> action) {
        ShardRuntime runtime = shardRuntime.getIfAvailable();
        return runtime == null ? action.get() : runtime.readYourWrite(action);
    }
}