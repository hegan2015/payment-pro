package com.qoder.payment.accounting.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * AC-6 币种账套目录（accounting_currency）。<b>不分片</b>（全局元数据）。
 *
 * <p>trial() 按本目录逐币种独立试算——<b>绝不对 ledger_entry/account_balance 做跨币种 GROUP BY</b>
 * （分片键是 account_id，非分片键分组会触发跨片归并风险）；每币种一条顶层 SUM/COUNT 查询，
 * 与既有 sumDebit()/sumBalance() 同一归并口径，单库/分片两态结果一致。
 *
 * <p>只 INSERT IGNORE（幂等补齐），绝不 DELETE/UPDATE 既有行。
 */
@Mapper
public interface CurrencyRegistryMapper {

    @Insert("INSERT INTO accounting_currency(currency,base_flag,created_at) VALUES(#{currency},#{baseFlag},#{createdAt})")
    int insert(@org.apache.ibatis.annotations.Param("currency") String currency,
               @org.apache.ibatis.annotations.Param("baseFlag") boolean baseFlag,
               @org.apache.ibatis.annotations.Param("createdAt") long createdAt);

    /** CNY（本位币）恒排首位，其余按字母序——保证 byCurrency 顺序稳定可断言。 */
    @Select("SELECT currency FROM accounting_currency ORDER BY base_flag DESC, currency")
    List<String> selectAll();

    @Select("SELECT COUNT(*) FROM accounting_currency WHERE currency=#{currency}")
    int countByCurrency(String currency);
}