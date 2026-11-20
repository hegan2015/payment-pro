package com.qoder.payment.accounting.mapper;

import com.qoder.payment.accounting.dataobject.DayEndReportDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** AC-3 日终报告（<b>不分片</b>：按会计日一行，派生值可幂等重算，绝不 DELETE）。 */
@Mapper
public interface DayEndReportMapper {

    @Insert("INSERT INTO day_end_report(biz_date,income_total,expense_total,profit,transferred,transfer_biz_no,"
            + "debit_total,credit_total,balance_sum,subject_count,entry_count,legacy_entry_count,status,updated_at) "
            + "VALUES(#{bizDate},#{incomeTotal},#{expenseTotal},#{profit},#{transferred},#{transferBizNo},"
            + "#{debitTotal},#{creditTotal},#{balanceSum},#{subjectCount},#{entryCount},#{legacyEntryCount},"
            + "#{status},#{updatedAt})")
    int insert(DayEndReportDO report);

    @Update("UPDATE day_end_report SET income_total=#{incomeTotal},expense_total=#{expenseTotal},profit=#{profit},"
            + "transferred=#{transferred},transfer_biz_no=#{transferBizNo},debit_total=#{debitTotal},"
            + "credit_total=#{creditTotal},balance_sum=#{balanceSum},subject_count=#{subjectCount},"
            + "entry_count=#{entryCount},legacy_entry_count=#{legacyEntryCount},status=#{status},updated_at=#{updatedAt} "
            + "WHERE biz_date=#{bizDate}")
    int update(DayEndReportDO report);

    @Select("SELECT * FROM day_end_report WHERE biz_date=#{bizDate}")
    DayEndReportDO select(long bizDate);

    @Select("SELECT * FROM day_end_report ORDER BY biz_date DESC LIMIT #{limit}")
    List<DayEndReportDO> selectRecent(int limit);

    @Select("SELECT COUNT(*) FROM day_end_report")
    int count();
}