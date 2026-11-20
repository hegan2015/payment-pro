package com.qoder.payment.accounting.ledger;

import com.qoder.payment.accounting.dataobject.EntryDO;
import com.qoder.payment.accounting.dataobject.SubjectBalanceDO;
import com.qoder.payment.accounting.dataobject.SubjectDO;
import com.qoder.payment.accounting.dataobject.TransferDO;
import com.qoder.payment.accounting.daycut.AccountingDayClock;
import com.qoder.payment.accounting.daycut.DayEndBatchService;
import com.qoder.payment.accounting.mapper.EntryMapper;
import com.qoder.payment.accounting.mapper.SubjectBalanceMapper;
import com.qoder.payment.accounting.mapper.TransferMapper;
import com.qoder.payment.accounting.service.VoucherGuard;
import com.qoder.payment.accounting.subject.SubjectService;
import com.qoder.payment.common.db.ShardRuntime;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.accounting.EntryDTO;
import com.qoder.payment.facade.accounting.GeneralLedgerDTO;
import com.qoder.payment.facade.accounting.LedgerQuery;
import com.qoder.payment.facade.accounting.SubjectBalanceDTO;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.payment.facade.accounting.VoucherDTO;
import com.qoder.payment.facade.accounting.VoucherQuery;
import com.qoder.payment.facade.common.PageDTO;
import com.qoder.tcc.TccContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * AC-4 账簿/凭证查询（<b>全部只读</b>，凭证不可篡改红线：本服务不含任何写入口）。
 *
 * <ul>
 *   <li><b>总账</b> generalLedger：subject_balance（科目+会计日汇总，不分片）分页；</li>
 *   <li><b>明细账</b> detailLedger：ledger_entry（按 account_id 分片）逐笔分录，
 *       跨片 {@code ORDER BY seq DESC LIMIT/OFFSET} 由 ShardingSphere 归并 ⇒ 不重不漏；</li>
 *   <li><b>科目余额表</b> balanceSheet：明细科目期末余额 + 类别根汇总；未跑批时按同一口径实时归集；</li>
 *   <li><b>凭证联查</b> vouchers/voucher：transfer_record 头 + ledger_entry 分录 + 红冲关联，
 *       支持 bizNo/refId/科目/账户/会计日/日期区间。</li>
 * </ul>
 *
 * <p><b>跨片聚合红线</b>：所有 SUM 均为顶层投影（绝不藏进 COALESCE 等函数，I-5a/b 已踩坑）；
 * 科目/账户反查凭证用「普通投影 + Java 去重」两步查询，不做跨片子查询或 DISTINCT 归并。
 * 资金读一律 {@code readYourWrite} 强制主库（读己之写）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerQueryService {

    private static final int MAX_BIZ_NOS = 1000;

    private final EntryMapper entryMapper;
    private final TransferMapper transferMapper;
    private final SubjectBalanceMapper subjectBalanceMapper;
    private final SubjectService subjectService;
    private final DayEndBatchService dayEndBatchService;
    private final AccountingDayClock clock;
    private final ObjectProvider<ShardRuntime> shardRuntime;

    // ------------------------------------------------------------------ 总账

    public PageDTO<GeneralLedgerDTO> generalLedger(String subjectCode, Long bizDate, int page, int size) {
        int p = Math.max(page, 1);
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Long day = resolveDate(bizDate);
        long total = subjectBalanceMapper.countPage(subjectCode, day);
        List<SubjectBalanceDO> rows = subjectBalanceMapper.selectPage(subjectCode, day, s, (p - 1) * s);
        Map<String, SubjectDO> catalog = subjectService.catalogSnapshot();
        List<GeneralLedgerDTO> items = new ArrayList<>();
        for (SubjectBalanceDO row : rows) {
            items.add(toGeneralLedger(row, catalog.get(row.getSubjectCode())));
        }
        return PageDTO.of(items, p, s, total);
    }

    private static GeneralLedgerDTO toGeneralLedger(SubjectBalanceDO row, SubjectDO subject) {
        GeneralLedgerDTO dto = new GeneralLedgerDTO();
        dto.setSubjectCode(row.getSubjectCode());
        dto.setBizDate(row.getBizDate());
        dto.setOpeningBalance(row.getOpeningBalance());
        dto.setDebitTotal(row.getDebitTotal());
        dto.setCreditTotal(row.getCreditTotal());
        dto.setClosingBalance(row.getBalance());
        dto.setEntryCount(row.getEntryCount());
        dto.setAccountCount(row.getAccountCount());
        if (subject != null) {
            dto.setSubjectName(subject.getName());
            dto.setType(subject.getType());
            dto.setDirection(subject.getDirection());
            dto.setParentCode(subject.getParentCode());
            dto.setLevel(subject.getSubjectLevel());
        }
        return dto;
    }

    // ------------------------------------------------------------------ 明细账

    public PageDTO<EntryDTO> detailLedger(LedgerQuery query) {
        LedgerQuery q = query == null ? new LedgerQuery() : query;
        q.setPage(Math.max(q.getPage(), 1));
        q.setSize(q.getSize() <= 0 ? 20 : Math.min(q.getSize(), 200));
        return readYourWrite(() -> {
            long total = entryMapper.countLedgerPage(q);
            List<EntryDTO> items = entryMapper.selectLedgerPage(q).stream().map(LedgerQueryService::toEntryDTO).toList();
            return PageDTO.of(items, q.getPage(), q.getSize(), total);
        });
    }

    static EntryDTO toEntryDTO(EntryDO e) {
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
        // AC-6 additive：币种账套 + 入账时汇率快照 + 本位币折算额（存量行为 null）
        dto.setCurrency(e.getCurrency());
        dto.setExchangeRate(e.getExchangeRate());
        dto.setRateScale(e.getRateScale());
        dto.setBaseAmount(e.getBaseAmount());
        return dto;
    }

    // ------------------------------------------------------------------ 科目余额表

    /** 余额表：明细科目（level=2）+ 类别根（level=1，按 parent 汇总）。未跑批时实时归集（只读）。 */
    public List<SubjectBalanceDTO> balanceSheet(Long bizDate) {
        long day = resolveDate(bizDate) == null ? clock.currentDay() : resolveDate(bizDate);
        Map<String, SubjectDO> catalog = subjectService.catalogSnapshot();
        List<SubjectBalanceDO> stored = subjectBalanceMapper.selectByDate(day);
        Map<String, SubjectBalanceDO> leaves = new LinkedHashMap<>();
        if (stored.isEmpty()) {
            leaves.putAll(dayEndBatchService.compute(day));
        } else {
            for (SubjectBalanceDO row : stored) {
                leaves.put(row.getSubjectCode(), row);
            }
        }
        List<SubjectBalanceDTO> out = new ArrayList<>();
        for (SubjectBalanceDO row : leaves.values()) {
            out.add(toSubjectBalanceDTO(row, catalog.get(row.getSubjectCode())));
        }
        // 类别根汇总（只汇总直接子科目；根之和 = 明细之和 ⇒ 资产=负债+权益+损益 恒等可验）
        for (SubjectDO root : catalog.values()) {
            if (root.getSubjectLevel() == null || root.getSubjectLevel() != 1) {
                continue;
            }
            SubjectBalanceDO agg = new SubjectBalanceDO();
            agg.setSubjectCode(root.getSubjectCode());
            agg.setBizDate(day);
            agg.setDebitTotal(0L);
            agg.setCreditTotal(0L);
            agg.setBalance(0L);
            agg.setEntryCount(0);
            agg.setAccountCount(0);
            agg.setUpdatedAt(System.currentTimeMillis());
            for (SubjectBalanceDO leaf : leaves.values()) {
                SubjectDO def = catalog.get(leaf.getSubjectCode());
                if (def == null || !root.getSubjectCode().equals(def.getParentCode())) {
                    continue;
                }
                agg.setDebitTotal(agg.getDebitTotal() + nz(leaf.getDebitTotal()));
                agg.setCreditTotal(agg.getCreditTotal() + nz(leaf.getCreditTotal()));
                agg.setBalance(agg.getBalance() + nz(leaf.getBalance()));
                agg.setEntryCount(agg.getEntryCount() + (leaf.getEntryCount() == null ? 0 : leaf.getEntryCount()));
                agg.setAccountCount(agg.getAccountCount() + (leaf.getAccountCount() == null ? 0 : leaf.getAccountCount()));
            }
            agg.setOpeningBalance(agg.getBalance() - (agg.getCreditTotal() - agg.getDebitTotal()));
            out.add(toSubjectBalanceDTO(agg, root));
        }
        out.sort((a, b) -> a.getSubjectCode().compareTo(b.getSubjectCode()));
        return out;
    }

    public SubjectBalanceDTO subjectBalance(String subjectCode, Long bizDate) {
        SubjectDO subject = subjectService.requireLeaf(subjectCode);
        long day = resolveDate(bizDate) == null ? clock.currentDay() : resolveDate(bizDate);
        SubjectBalanceDO row = subjectBalanceMapper.select(subjectCode, day);
        if (row == null) {
            row = dayEndBatchService.compute(day).get(subjectCode);
        }
        if (row == null) {
            throw new BizException(ResultCode.NOT_FOUND, "科目余额不存在: " + subjectCode);
        }
        return toSubjectBalanceDTO(row, subject);
    }

    private static SubjectBalanceDTO toSubjectBalanceDTO(SubjectBalanceDO row, SubjectDO subject) {
        SubjectBalanceDTO dto = new SubjectBalanceDTO();
        dto.setSubjectCode(row.getSubjectCode());
        dto.setBizDate(row.getBizDate());
        dto.setOpeningBalance(row.getOpeningBalance());
        dto.setDebitTotal(row.getDebitTotal());
        dto.setCreditTotal(row.getCreditTotal());
        dto.setBalance(row.getBalance());
        dto.setEntryCount(row.getEntryCount());
        dto.setAccountCount(row.getAccountCount());
        dto.setUpdatedAt(row.getUpdatedAt());
        if (subject != null) {
            dto.setSubjectName(subject.getName());
            dto.setType(subject.getType());
            dto.setDirection(subject.getDirection());
            dto.setParentCode(subject.getParentCode());
            dto.setLevel(subject.getSubjectLevel());
        }
        return dto;
    }

    // ------------------------------------------------------------------ 凭证联查（只读）

    public PageDTO<VoucherDTO> vouchers(VoucherQuery query) {
        VoucherQuery q = query == null ? new VoucherQuery() : query;
        q.setPage(Math.max(q.getPage(), 1));
        q.setSize(q.getSize() <= 0 ? 20 : Math.min(q.getSize(), 200));
        List<String> bizNos = null;
        if (notBlank(q.getSubjectCode()) || notBlank(q.getAccountId())) {
            Set<String> resolved = new LinkedHashSet<>();
            if (notBlank(q.getSubjectCode())) {
                resolved.addAll(readYourWrite(() -> entryMapper.selectBizNosBySubject(
                        q.getSubjectCode(), q.getDateFrom(), q.getDateTo(), MAX_BIZ_NOS)));
            }
            if (notBlank(q.getAccountId())) {
                Set<String> byAccount = new LinkedHashSet<>(readYourWrite(
                        () -> entryMapper.selectBizNosByAccount(q.getAccountId(), MAX_BIZ_NOS)));
                if (notBlank(q.getSubjectCode())) {
                    resolved.retainAll(byAccount);
                } else {
                    resolved.addAll(byAccount);
                }
            }
            if (resolved.isEmpty()) {
                return PageDTO.of(List.of(), q.getPage(), q.getSize(), 0);
            }
            bizNos = new ArrayList<>(resolved);
        }
        final List<String> filter = bizNos;
        return readYourWrite(() -> {
            long total = transferMapper.countVoucherPage(q, filter);
            List<TransferDO> rows = transferMapper.selectVoucherPage(q, filter, q.getSize(), q.getOffset());
            List<VoucherDTO> items = new ArrayList<>();
            for (TransferDO row : rows) {
                items.add(toVoucher(row, q.isWithEntries()));
            }
            return PageDTO.of(items, q.getPage(), q.getSize(), total);
        });
    }

    public VoucherDTO voucher(String bizNo) {
        TransferDO row = transferMapper.selectByBizNo(bizNo);
        if (row == null) {
            throw new BizException(ResultCode.NOT_FOUND, "凭证不存在: " + bizNo);
        }
        return readYourWrite(() -> toVoucher(row, true));
    }

    private VoucherDTO toVoucher(TransferDO row, boolean withEntries) {
        VoucherDTO dto = new VoucherDTO();
        dto.setBizNo(row.getBizNo());
        dto.setSubject(row.getSubject());
        dto.setRefId(row.getRefId());
        dto.setReversed(row.getReversed());
        dto.setVoucherType(row.getVoucherType() == null ? VoucherGuard.NORMAL : row.getVoucherType());
        dto.setBizDate(row.getBizDate());
        dto.setCreatedAt(row.getCreatedAt());
        dto.setLines(TccContextUtil.fromJsonList(row.getLines(), TransferLineDTO.class));
        if (VoucherGuard.REVERSE.equals(dto.getVoucherType())) {
            dto.setOrigBizNo(row.getRefId());
        } else {
            TransferDO reverse = transferMapper.selectReverseByOrig(row.getBizNo());
            dto.setReverseBizNo(reverse == null ? null : reverse.getBizNo());
        }
        long debit = 0;
        long credit = 0;
        Set<String> codes = new TreeSet<>();
        List<EntryDTO> entries = new ArrayList<>();
        if (withEntries) {
            for (EntryDO e : entryMapper.selectByBizNo(row.getBizNo())) {
                entries.add(toEntryDTO(e));
                if ("D".equals(e.getDir())) {
                    debit += nz(e.getAmount());
                } else {
                    credit += nz(e.getAmount());
                }
                if (e.getSubjectCode() != null) {
                    codes.add(e.getSubjectCode());
                }
            }
        } else {
            for (TransferLineDTO line : dto.getLines()) {
                if ("D".equals(line.getDir())) {
                    debit += nz(line.getAmount());
                } else {
                    credit += nz(line.getAmount());
                }
            }
        }
        dto.setEntries(entries);
        dto.setTotalDebit(debit);
        dto.setTotalCredit(credit);
        dto.setBalanced(debit == credit);
        dto.setSubjectCodes(new ArrayList<>(codes));
        // AC-6 additive：凭证币种与汇率快照（一张凭证一个账套，存量凭证为 null）
        dto.setCurrency(row.getCurrency());
        dto.setExchangeRate(row.getExchangeRate());
        dto.setRateScale(row.getRateScale());
        dto.setBaseAmount(row.getBaseAmount());
        return dto;
    }

    // ------------------------------------------------------------------ 工具

    private Long resolveDate(Long bizDate) {
        if (bizDate != null) {
            return AccountingDayClock.dayStart(bizDate);
        }
        return subjectBalanceMapper.maxDate();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private <T> T readYourWrite(Supplier<T> action) {
        ShardRuntime runtime = shardRuntime.getIfAvailable();
        return runtime == null ? action.get() : runtime.readYourWrite(action);
    }
}