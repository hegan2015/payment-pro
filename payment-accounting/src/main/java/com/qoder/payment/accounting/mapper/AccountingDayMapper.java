package com.qoder.payment.accounting.mapper;

import com.qoder.payment.accounting.dataobject.AccountingDayDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * AC-3 会计日（<b>不分片</b>：全局会计日历，多实例共享同一行 ⇒ CAS 天然互斥）。
 *
 * <p>状态机 OPEN→CUTTING→CLOSED 全部经 {@code UPDATE ... WHERE status=?} 乐观 CAS，
 * 配合 ShedLock 保证多实例不重复日切；绝不 DELETE 既有日行。
 */
@Mapper
public interface AccountingDayMapper {

    @Insert("INSERT INTO accounting_day(biz_date,status,opened_at,cut_at,closed_at,prev_balance_snapshot,"
            + "balance_sum,total_debit,total_credit,accounts,entries) "
            + "VALUES(#{bizDate},#{status},#{openedAt},#{cutAt},#{closedAt},#{prevBalanceSnapshot},"
            + "#{balanceSum},#{totalDebit},#{totalCredit},#{accounts},#{entries})")
    int insert(AccountingDayDO day);

    @Select("SELECT * FROM accounting_day WHERE biz_date=#{bizDate}")
    AccountingDayDO select(long bizDate);

    @Select("SELECT * FROM accounting_day WHERE status='OPEN' ORDER BY biz_date DESC LIMIT 1")
    AccountingDayDO selectOpen();

    @Select("SELECT * FROM accounting_day ORDER BY biz_date DESC LIMIT #{limit}")
    List<AccountingDayDO> selectRecent(@Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM accounting_day")
    int count();

    /** CAS：OPEN → CUTTING（返回 1 = 本次抢到日切权）。 */
    @Update("UPDATE accounting_day SET status='CUTTING', cut_at=#{cutAt} "
            + "WHERE biz_date=#{bizDate} AND status='OPEN'")
    int casToCutting(@Param("bizDate") long bizDate, @Param("cutAt") long cutAt);

    /** CAS：CUTTING → CLOSED（落快照与试算结果）。 */
    @Update("UPDATE accounting_day SET status='CLOSED', closed_at=#{closedAt}, "
            + "prev_balance_snapshot=#{prevBalanceSnapshot}, balance_sum=#{balanceSum}, "
            + "total_debit=#{totalDebit}, total_credit=#{totalCredit}, accounts=#{accounts}, entries=#{entries} "
            + "WHERE biz_date=#{bizDate} AND status='CUTTING'")
    int casToClosed(AccountingDayDO day);

    /** 快照落库（CUTTING 阶段，失败重跑可覆盖派生快照列；不动余额）。 */
    @Update("UPDATE accounting_day SET prev_balance_snapshot=#{prevBalanceSnapshot}, balance_sum=#{balanceSum}, "
            + "total_debit=#{totalDebit}, total_credit=#{totalCredit}, accounts=#{accounts}, entries=#{entries} "
            + "WHERE biz_date=#{bizDate}")
    int updateSnapshot(AccountingDayDO day);
}