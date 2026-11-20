package com.qoder.payment.accounting.mapper;

import com.qoder.payment.accounting.dataobject.FxRateDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * A-4 汇率版本表（fx_rate）。<b>不分片</b>（全局市场元数据，与账户余额无关）。
 *
 * <p><b>只追加</b>：历史版本永不 UPDATE/DELETE —— 已入账分录引用的汇率快照必须可回放。
 * 唯一键 (from_currency,to_currency,version)；生效时间 effective_at 决定「当前生效版本」。
 */
@Mapper
public interface FxRateMapper {

    @Insert("INSERT INTO fx_rate(id,from_currency,to_currency,buy_rate,sell_rate,mid_rate,rate_scale,version,"
            + "effective_at,biz_date,source,operator,created_at) "
            + "VALUES(#{id},#{fromCurrency},#{toCurrency},#{buyRate},#{sellRate},#{midRate},#{rateScale},#{version},"
            + "#{effectiveAt},#{bizDate},#{source},#{operator},#{createdAt})")
    int insert(FxRateDO rate);

    @Select("SELECT next_value_for('seq_fx_rate')")
    long nextId();

    /** 当前生效版本：effective_at ≤ now 中生效时间最新者（同时间取版本号最大）。 */
    @Select("SELECT * FROM fx_rate WHERE from_currency=#{from} AND to_currency=#{to} AND effective_at<=#{now} "
            + "ORDER BY effective_at DESC, version DESC LIMIT 1")
    FxRateDO selectEffective(@Param("from") String from, @Param("to") String to, @Param("now") long now);

    @Select("SELECT * FROM fx_rate WHERE from_currency=#{from} AND to_currency=#{to} AND version=#{version}")
    FxRateDO selectByVersion(@Param("from") String from, @Param("to") String to, @Param("version") long version);

    @Select("SELECT MAX(version) FROM fx_rate WHERE from_currency=#{from} AND to_currency=#{to}")
    Long maxVersion(@Param("from") String from, @Param("to") String to);

    @Select("<script>SELECT * FROM fx_rate "
            + "<where><if test='from != null and from != \"\"'> AND from_currency=#{from}</if>"
            + "<if test='to != null and to != \"\"'> AND to_currency=#{to}</if></where>"
            + " ORDER BY created_at DESC, version DESC LIMIT #{limit}</script>")
    List<FxRateDO> selectRecent(@Param("from") String from, @Param("to") String to, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM fx_rate")
    int count();
}