package com.qoder.payment.accounting.subject;

import com.qoder.payment.accounting.dataobject.AccountSubjectMapDO;
import com.qoder.payment.accounting.dataobject.SubjectDO;
import com.qoder.payment.accounting.mapper.SubjectMapper;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.accounting.SubjectDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AC-2 会计科目体系：科目目录 + 账户→科目映射 + 分录科目解析。
 *
 * <p><b>口径不变红线</b>：本服务只产出 {@code subject_code} 标签，绝不参与余额/冻结/借贷方向计算。
 * 系统户映射固定（{@link AccountSubjectMapping}），客户账户默认 2001 客户余额。
 * 科目目录启动时幂等补齐（存在即跳过，绝不覆盖既有行、绝不 DELETE）。
 *
 * <p>解析走进程内缓存（每笔分录都要用），映射变更即时失效；缓存未命中回落数据库，
 * 数据库不可达时回落内置默认科目——<b>绝不因科目体系故障阻断过账</b>（资金可用性优先）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@DependsOn("accountingSchemaUpgrade")
public class SubjectService implements InitializingBean {

    private final SubjectMapper subjectMapper;

    private final Map<String, String> accountSubjectCache = new ConcurrentHashMap<>();
    private volatile boolean seeded = false;

    @Override
    public void afterPropertiesSet() {
        seed();
    }

    /** 幂等补齐内置科目与系统户固定映射（只 INSERT 缺失行）。 */
    public synchronized void seed() {
        long now = System.currentTimeMillis();
        int subjects = 0;
        int maps = 0;
        for (AccountSubjectMapping.Def def : AccountSubjectMapping.catalog()) {
            if (subjectMapper.selectByCode(def.code()) == null) {
                SubjectDO row = new SubjectDO();
                row.setSubjectCode(def.code());
                row.setName(def.name());
                row.setType(def.type());
                row.setDirection(def.direction());
                row.setParentCode(def.parent());
                row.setSubjectLevel(def.level());
                row.setSystemAccount(def.systemAccount());
                row.setSystemFlag(def.systemAccount() != null);
                row.setStatus("ENABLED");
                row.setCreatedAt(now);
                try {
                    subjectMapper.insert(row);
                    subjects++;
                } catch (DuplicateKeyException ignored) {
                    // 并发启动竞争：对手已插入，保留既有行
                }
            }
            if (def.systemAccount() != null && subjectMapper.selectMap(def.systemAccount()) == null) {
                AccountSubjectMapDO map = new AccountSubjectMapDO();
                map.setAccountId(def.systemAccount());
                map.setSubjectCode(def.code());
                map.setCreatedAt(now);
                try {
                    subjectMapper.insertMap(map);
                    maps++;
                } catch (DuplicateKeyException ignored) {
                    // 同上
                }
            }
        }
        accountSubjectCache.clear();
        seeded = true;
        log.info("AC-2 科目目录就绪 subjects={} newSubjects={} newSystemMaps={} provider=builtin",
                subjectMapper.count(), subjects, maps);
    }

    /**
     * 账户 → 科目代码（分录落科目用）。系统户固定映射优先且不可改；
     * 客户账户读显式映射，缺省 2001；任何异常回落默认科目，绝不阻断过账。
     */
    public String subjectOf(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return AccountSubjectMapping.UNMAPPED;
        }
        if (AccountSubjectMapping.isSystem(accountId)) {
            AccountSubjectMapping.Def def = AccountSubjectMapping.bySystemAccount(accountId);
            return def == null ? AccountSubjectMapping.UNMAPPED : def.code();
        }
        String cached = accountSubjectCache.get(accountId);
        if (cached != null) {
            return cached;
        }
        String resolved = AccountSubjectMapping.CUSTOMER_BALANCE;
        try {
            AccountSubjectMapDO map = subjectMapper.selectMap(accountId);
            if (map != null && map.getSubjectCode() != null && !map.getSubjectCode().isBlank()) {
                resolved = map.getSubjectCode();
            }
        } catch (Exception e) {
            log.warn("AC-2 科目映射读取失败，回落默认科目 {} accountId={} err={}",
                    AccountSubjectMapping.CUSTOMER_BALANCE, accountId, e.toString());
        }
        accountSubjectCache.put(accountId, resolved);
        return resolved;
    }

    public List<SubjectDTO> listSubjects() {
        List<SubjectDTO> out = new ArrayList<>();
        for (SubjectDO row : subjectMapper.selectAll()) {
            out.add(toDTO(row));
        }
        return out;
    }

    public SubjectDTO getSubject(String subjectCode) {
        SubjectDO row = subjectMapper.selectByCode(subjectCode);
        if (row == null) {
            throw new BizException(ResultCode.NOT_FOUND, "科目不存在: " + subjectCode);
        }
        return toDTO(row);
    }

    public SubjectDO requireLeaf(String subjectCode) {
        SubjectDO row = subjectMapper.selectByCode(subjectCode);
        if (row == null) {
            throw new BizException(ResultCode.NOT_FOUND, "科目不存在: " + subjectCode);
        }
        if (!"ENABLED".equals(row.getStatus())) {
            throw new BizException(ResultCode.BIZ_ERROR, "科目已停用: " + subjectCode);
        }
        if (row.getSubjectLevel() == null || row.getSubjectLevel() != 2) {
            throw new BizException(ResultCode.PARAM_ERROR, "只能挂明细科目（level=2）: " + subjectCode);
        }
        return row;
    }

    /**
     * 账户→科目改挂：系统户固定映射不可改（红线）；目标必须是启用的明细科目。
     * 只写映射元数据，<b>不动余额、不动既有分录</b>（历史分录科目保持落库时真值）。
     */
    public SubjectDTO mapAccountSubject(String accountId, String subjectCode) {
        if (accountId == null || accountId.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "accountId 必填");
        }
        if (AccountSubjectMapping.isSystem(accountId)) {
            AccountSubjectMapping.Def fixed = AccountSubjectMapping.bySystemAccount(accountId);
            String fixedCode = fixed == null ? AccountSubjectMapping.UNMAPPED : fixed.code();
            if (!fixedCode.equals(subjectCode)) {
                throw new BizException(ResultCode.BIZ_ERROR,
                        "系统户科目固定映射不可改: " + accountId + " → " + fixedCode);
            }
            return getSubject(fixedCode);
        }
        SubjectDO target = requireLeaf(subjectCode);
        AccountSubjectMapDO map = new AccountSubjectMapDO();
        map.setAccountId(accountId);
        map.setSubjectCode(subjectCode);
        map.setCreatedAt(System.currentTimeMillis());
        if (subjectMapper.selectMap(accountId) == null) {
            try {
                subjectMapper.insertMap(map);
            } catch (DuplicateKeyException e) {
                subjectMapper.updateMap(map);
            }
        } else {
            subjectMapper.updateMap(map);
        }
        accountSubjectCache.put(accountId, subjectCode);
        log.info("AC-2 账户科目改挂 accountId={} subjectCode={}（只改映射元数据，不动余额与既有分录）",
                accountId, subjectCode);
        return toDTO(target);
    }

    /** 科目目录快照（code → DO），日终汇总用。 */
    public Map<String, SubjectDO> catalogSnapshot() {
        Map<String, SubjectDO> out = new ConcurrentHashMap<>();
        for (SubjectDO row : subjectMapper.selectAll()) {
            out.put(row.getSubjectCode(), row);
        }
        return out;
    }

    public boolean seeded() {
        return seeded;
    }

    public int mappedAccounts() {
        return subjectMapper.selectAllMaps().size();
    }

    public List<AccountSubjectMapDO> mappings() {
        return subjectMapper.selectAllMaps();
    }

    public static SubjectDTO toDTO(SubjectDO row) {
        SubjectDTO dto = new SubjectDTO();
        dto.setSubjectCode(row.getSubjectCode());
        dto.setName(row.getName());
        dto.setType(row.getType());
        dto.setDirection(row.getDirection());
        dto.setParentCode(row.getParentCode());
        dto.setLevel(row.getSubjectLevel());
        dto.setSystemAccount(row.getSystemAccount());
        dto.setSystemFlag(row.getSystemFlag());
        dto.setStatus(row.getStatus());
        dto.setCreatedAt(row.getCreatedAt());
        return dto;
    }
}