package com.qoder.payment.accounting.mapper;

import com.qoder.payment.accounting.dataobject.AccountSubjectMapDO;
import com.qoder.payment.accounting.dataobject.SubjectDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * AC-2 科目目录 + 账户→科目映射（<b>不分片</b>：科目是全局元数据，与账户余额无关）。
 *
 * <p>只 INSERT / UPDATE 元数据；科目目录启动时幂等补齐（存在即跳过，绝不覆盖既有行）。
 * 系统户固定映射不可改（由 SubjectService 守卫），客户账户默认 2001 客户余额。
 */
@Mapper
public interface SubjectMapper {

    @Insert("INSERT INTO accounting_subject(subject_code,name,type,direction,parent_code,subject_level,"
            + "system_account,system_flag,status,created_at) "
            + "VALUES(#{subjectCode},#{name},#{type},#{direction},#{parentCode},#{subjectLevel},"
            + "#{systemAccount},#{systemFlag},#{status},#{createdAt})")
    int insert(SubjectDO subject);

    @Select("SELECT * FROM accounting_subject ORDER BY subject_code")
    List<SubjectDO> selectAll();

    @Select("SELECT * FROM accounting_subject WHERE subject_code=#{subjectCode}")
    SubjectDO selectByCode(String subjectCode);

    @Select("SELECT * FROM accounting_subject WHERE system_account=#{accountId}")
    SubjectDO selectBySystemAccount(String accountId);

    @Select("SELECT COUNT(*) FROM accounting_subject")
    int count();

    // ---------------- 账户→科目映射 ----------------

    @Insert("INSERT INTO account_subject_map(account_id,subject_code,created_at) "
            + "VALUES(#{accountId},#{subjectCode},#{createdAt})")
    int insertMap(AccountSubjectMapDO map);

    @Update("UPDATE account_subject_map SET subject_code=#{subjectCode} WHERE account_id=#{accountId}")
    int updateMap(AccountSubjectMapDO map);

    @Select("SELECT * FROM account_subject_map WHERE account_id=#{accountId}")
    AccountSubjectMapDO selectMap(String accountId);

    @Select("SELECT * FROM account_subject_map ORDER BY account_id")
    List<AccountSubjectMapDO> selectAllMaps();

    @Select("SELECT COUNT(*) FROM account_subject_map WHERE subject_code=#{subjectCode}")
    int countBySubject(String subjectCode);
}