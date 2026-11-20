package com.qoder.payment.accounting.mapper;

import com.qoder.payment.accounting.dataobject.SubjectBalanceDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * AC-2/AC-4 科目余额表（<b>不分片</b>：按科目+会计日汇总的派生行，全局唯一）。
 *
 * <p>日终批幂等重算：存在即 UPDATE（派生聚合值），不存在即 INSERT；<b>绝不 DELETE</b>。
 */
@Mapper
public interface SubjectBalanceMapper {

    @Insert("INSERT INTO subject_balance(subject_code,biz_date,opening_balance,debit_total,credit_total,"
            + "balance,entry_count,account_count,updated_at) "
            + "VALUES(#{subjectCode},#{bizDate},#{openingBalance},#{debitTotal},#{creditTotal},"
            + "#{balance},#{entryCount},#{accountCount},#{updatedAt})")
    int insert(SubjectBalanceDO row);

    @Update("UPDATE subject_balance SET opening_balance=#{openingBalance},debit_total=#{debitTotal},"
            + "credit_total=#{creditTotal},balance=#{balance},entry_count=#{entryCount},"
            + "account_count=#{accountCount},updated_at=#{updatedAt} "
            + "WHERE subject_code=#{subjectCode} AND biz_date=#{bizDate}")
    int update(SubjectBalanceDO row);

    @Select("SELECT * FROM subject_balance WHERE subject_code=#{subjectCode} AND biz_date=#{bizDate}")
    SubjectBalanceDO select(@Param("subjectCode") String subjectCode, @Param("bizDate") long bizDate);

    @Select("SELECT * FROM subject_balance WHERE biz_date=#{bizDate} ORDER BY subject_code")
    List<SubjectBalanceDO> selectByDate(long bizDate);

    @Select("<script>SELECT * FROM subject_balance "
            + "<where><if test='bizDate != null'> AND biz_date=#{bizDate}</if>"
            + "<if test='subjectCode != null and subjectCode != \"\"'> AND subject_code=#{subjectCode}</if></where>"
            + " ORDER BY biz_date DESC, subject_code LIMIT #{size} OFFSET #{offset}</script>")
    List<SubjectBalanceDO> selectPage(@Param("subjectCode") String subjectCode, @Param("bizDate") Long bizDate,
                                      @Param("size") int size, @Param("offset") int offset);

    @Select("<script>SELECT COUNT(*) FROM subject_balance "
            + "<where><if test='bizDate != null'> AND biz_date=#{bizDate}</if>"
            + "<if test='subjectCode != null and subjectCode != \"\"'> AND subject_code=#{subjectCode}</if></where>"
            + "</script>")
    long countPage(@Param("subjectCode") String subjectCode, @Param("bizDate") Long bizDate);

    @Select("SELECT MAX(biz_date) FROM subject_balance")
    Long maxDate();

    @Select("SELECT COUNT(*) FROM subject_balance")
    int count();
}