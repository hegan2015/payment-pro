package com.qoder.payment.accounting.mapper;

import com.qoder.payment.accounting.dataobject.BalanceDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface BalanceMapper {

    @Select("SELECT * FROM account_balance WHERE account_id=#{accountId} FOR UPDATE")
    BalanceDO selectForUpdate(String accountId);

    @Select("SELECT * FROM account_balance WHERE account_id=#{accountId}")
    BalanceDO select(String accountId);

    @Insert("INSERT INTO account_balance(account_id,balance,frozen,version,updated_at,currency) "
            + "VALUES(#{accountId},#{balance},#{frozen},#{version},#{updatedAt},#{currency})")
    int insert(BalanceDO balance);

    @Update("UPDATE account_balance SET balance=#{balance}, frozen=#{frozen}, version=version+1, updated_at=#{updatedAt} "
            + "WHERE account_id=#{accountId}")
    int update(BalanceDO balance);

    @Select("SELECT * FROM account_balance ORDER BY account_id")
    List<BalanceDO> selectAll();

    // I-5b 跨片聚合：SUM 必须作为**顶层投影**，ShardingSphere 归并引擎才会「各片 SUM 再相加」。
    // COALESCE(SUM(x),0) 把聚合藏在函数里会被当普通投影 → 返回每片一行（selectOne 报 TooManyResults）。
    // 空表时 SUM 返回 NULL，由 AccountingService 统一 nullToZero，单库/分片两端结果完全一致（与 I-5a settle 同口径）。
    @Select("SELECT SUM(balance) FROM account_balance")
    Long sumBalance();

    @Select("SELECT COUNT(*) FROM account_balance")
    int count();

    // ------------------------------------------------------------------
    // AC-6 多币种账套聚合（additive）：每币种一条顶层 SUM/COUNT + WHERE，
    // 与上面 sumBalance()/count() 同一跨片归并口径；**不做非分片键 GROUP BY**。
    // 存量 currency IS NULL 归入 CNY 账套 ⇒ 单币种现状下与改造前逐值相同。
    // ------------------------------------------------------------------

    String CCY_FILTER = "<choose><when test='currency == \"CNY\"'>(currency IS NULL OR currency='CNY')</when>"
            + "<otherwise>currency=#{currency}</otherwise></choose>";

    @Select("<script>SELECT SUM(balance) FROM account_balance WHERE " + CCY_FILTER + "</script>")
    Long sumBalanceByCurrency(@Param("currency") String currency);

    @Select("<script>SELECT SUM(ABS(balance)) FROM account_balance WHERE " + CCY_FILTER + "</script>")
    Long sumAbsBalanceByCurrency(@Param("currency") String currency);

    @Select("<script>SELECT COUNT(*) FROM account_balance WHERE " + CCY_FILTER + "</script>")
    int countByCurrency(@Param("currency") String currency);
}