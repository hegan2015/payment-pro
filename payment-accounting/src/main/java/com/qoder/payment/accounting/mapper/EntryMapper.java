package com.qoder.payment.accounting.mapper;

import com.qoder.payment.accounting.dataobject.AccountDayTotalDO;
import com.qoder.payment.accounting.dataobject.EntryDO;
import com.qoder.payment.facade.accounting.EntryQuery;
import com.qoder.payment.facade.accounting.LedgerQuery;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EntryMapper {

    String FILTER = "<where>"
            + "<if test='accountId != null and accountId != \"\"'> AND account_id = #{accountId}</if>"
            + "<if test='bizNo != null and bizNo != \"\"'> AND biz_no = #{bizNo}</if>"
            + "<if test='dir != null and dir != \"\"'> AND dir = #{dir}</if>"
            + "<if test='keyword != null and keyword != \"\"'> AND (subject LIKE CONCAT('%',#{keyword},'%') "
            + "OR ref_id LIKE CONCAT('%',#{keyword},'%') OR account_id LIKE CONCAT('%',#{keyword},'%') "
            + "OR biz_no LIKE CONCAT('%',#{keyword},'%'))</if>"
            // AC-2/AC-3/AC-4 additive 筛选：全部可选，为 null 时 SQL 与改造前逐字一致
            + "<if test='subjectCode != null and subjectCode != \"\"'> AND subject_code = #{subjectCode}</if>"
            + "<if test='bizDate != null'> AND biz_date = #{bizDate}</if>"
            + "<if test='dateFrom != null'> AND created_at &gt;= #{dateFrom}</if>"
            + "<if test='dateTo != null'> AND created_at &lt; #{dateTo}</if>"
            + "</where>";

    @Select("<script>SELECT * FROM ledger_entry " + FILTER + " ORDER BY seq DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<EntryDO> selectPage(EntryQuery query);

    @Select("<script>SELECT COUNT(*) FROM ledger_entry " + FILTER + "</script>")
    long countPage(EntryQuery query);

    @Select("SELECT next_value_for('seq_entry')")
    long nextId();

    // AC-2/AC-3：新增 subject_code / biz_date 两列（additive）；其余列与顺序逐字不变。
    // AC-6：再增 currency / exchange_rate / rate_scale / base_amount 四个标签列（同为 additive）。
    @Insert("INSERT INTO ledger_entry(id,seq,biz_no,account_id,dir,amount,balance_after,subject,ref_id,created_at,"
            + "subject_code,biz_date,currency,exchange_rate,rate_scale,base_amount) "
            + "VALUES(#{id},#{seq},#{bizNo},#{accountId},#{dir},#{amount},#{balanceAfter},#{subject},#{refId},"
            + "#{createdAt},#{subjectCode},#{bizDate},#{currency},#{exchangeRate},#{rateScale},#{baseAmount})")
    int insert(EntryDO entry);

    @Select("SELECT * FROM ledger_entry WHERE account_id=#{accountId} ORDER BY seq DESC LIMIT #{limit}")
    List<EntryDO> selectByAccount(@Param("accountId") String accountId, @Param("limit") int limit);

    @Select("SELECT * FROM ledger_entry WHERE biz_no=#{bizNo} ORDER BY seq")
    List<EntryDO> selectByBizNo(String bizNo);

    // I-5b 跨片聚合：SUM 必须是顶层投影（同 BalanceMapper 说明），NULL 由服务层 nullToZero。
    @Select("SELECT SUM(CASE WHEN dir='D' THEN amount ELSE 0 END) FROM ledger_entry")
    Long sumDebit();

    @Select("SELECT SUM(CASE WHEN dir='C' THEN amount ELSE 0 END) FROM ledger_entry")
    Long sumCredit();

    @Select("SELECT COUNT(*) FROM ledger_entry")
    int count();

    // ------------------------------------------------------------------
    // AC-6 多币种账套聚合（additive）：每币种一条顶层 SUM/COUNT + WHERE，
    // 与上面 sumDebit()/sumCredit()/count() 同一跨片归并口径；**不做非分片键 GROUP BY**。
    // 存量 currency IS NULL 归入 CNY 账套 ⇒ 单币种现状下与改造前逐值相同。
    // ------------------------------------------------------------------

    String CCY_FILTER = "<choose><when test='currency == \"CNY\"'>(currency IS NULL OR currency='CNY')</when>"
            + "<otherwise>currency=#{currency}</otherwise></choose>";

    @Select("<script>SELECT SUM(CASE WHEN dir='D' THEN amount ELSE 0 END) FROM ledger_entry WHERE "
            + CCY_FILTER + "</script>")
    Long sumDebitByCurrency(@Param("currency") String currency);

    @Select("<script>SELECT SUM(CASE WHEN dir='C' THEN amount ELSE 0 END) FROM ledger_entry WHERE "
            + CCY_FILTER + "</script>")
    Long sumCreditByCurrency(@Param("currency") String currency);

    @Select("<script>SELECT COUNT(*) FROM ledger_entry WHERE " + CCY_FILTER + "</script>")
    int countByCurrency(@Param("currency") String currency);

    // ------------------------------------------------------------------
    // AC-3/AC-4 账簿查询与日终汇总（跨片归并）
    // ------------------------------------------------------------------

    String LEDGER_FILTER = "<where>"
            + "<if test='accountId != null and accountId != \"\"'> AND account_id = #{accountId}</if>"
            + "<if test='subjectCode != null and subjectCode != \"\"'> AND subject_code = #{subjectCode}</if>"
            + "<if test='bizNo != null and bizNo != \"\"'> AND biz_no = #{bizNo}</if>"
            + "<if test='refId != null and refId != \"\"'> AND ref_id = #{refId}</if>"
            + "<if test='dir != null and dir != \"\"'> AND dir = #{dir}</if>"
            + "<if test='bizDate != null'> AND biz_date = #{bizDate}</if>"
            + "<if test='dateFrom != null'> AND created_at &gt;= #{dateFrom}</if>"
            + "<if test='dateTo != null'> AND created_at &lt; #{dateTo}</if>"
            + "</where>";

    /** AC-4 明细账：跨片 ORDER BY seq DESC + LIMIT/OFFSET 由归并引擎处理（不重不漏）。 */
    @Select("<script>SELECT * FROM ledger_entry " + LEDGER_FILTER
            + " ORDER BY seq DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<EntryDO> selectLedgerPage(LedgerQuery query);

    @Select("<script>SELECT COUNT(*) FROM ledger_entry " + LEDGER_FILTER + "</script>")
    long countLedgerPage(LedgerQuery query);

    /**
     * AC-3 日终按账户汇总：GROUP BY 分片键 account_id ⇒ 每个分组完整落在单片内，
     * ShardingSphere 分组归并不会出现「同组跨片部分聚合」；SUM 仍是顶层投影。
     */
    @Select("SELECT account_id, SUM(CASE WHEN dir='D' THEN amount ELSE 0 END) AS debit_total, "
            + "SUM(CASE WHEN dir='C' THEN amount ELSE 0 END) AS credit_total, COUNT(*) AS entry_count "
            + "FROM ledger_entry WHERE biz_date=#{bizDate} GROUP BY account_id")
    List<AccountDayTotalDO> sumByAccountForDay(long bizDate);

    /** AC-3 当日借方合计（顶层 SUM，跨片归并；与分组汇总互为对照）。 */
    @Select("SELECT SUM(CASE WHEN dir='D' THEN amount ELSE 0 END) FROM ledger_entry WHERE biz_date=#{bizDate}")
    Long sumDebitForDay(long bizDate);

    @Select("SELECT SUM(CASE WHEN dir='C' THEN amount ELSE 0 END) FROM ledger_entry WHERE biz_date=#{bizDate}")
    Long sumCreditForDay(long bizDate);

    @Select("SELECT COUNT(*) FROM ledger_entry WHERE biz_date=#{bizDate}")
    int countForDay(long bizDate);

    /** 升级前存量分录（biz_date 为空）：只读计数，绝不回填改写。 */
    @Select("SELECT COUNT(*) FROM ledger_entry WHERE biz_date IS NULL")
    int countLegacy();

    /** AC-4 凭证按科目反查：普通投影（不做跨片子查询/DISTINCT 归并），Java 侧去重。 */
    @Select("<script>SELECT biz_no FROM ledger_entry WHERE subject_code=#{subjectCode} "
            + "<if test='dateFrom != null'> AND created_at &gt;= #{dateFrom}</if>"
            + "<if test='dateTo != null'> AND created_at &lt; #{dateTo}</if>"
            + " ORDER BY seq DESC LIMIT #{limit}</script>")
    List<String> selectBizNosBySubject(@Param("subjectCode") String subjectCode,
                                       @Param("dateFrom") Long dateFrom, @Param("dateTo") Long dateTo,
                                       @Param("limit") int limit);

    /** AC-4 凭证按账户反查（同上，普通投影 + Java 去重）。 */
    @Select("SELECT biz_no FROM ledger_entry WHERE account_id=#{accountId} ORDER BY seq DESC LIMIT #{limit}")
    List<String> selectBizNosByAccount(@Param("accountId") String accountId, @Param("limit") int limit);
}