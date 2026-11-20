package com.qoder.payment.accounting.mapper;

import com.qoder.payment.accounting.dataobject.FxExchangeDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * A-4 换汇单（fx_exchange）。<b>不分片</b>（全局幂等单据，与 idempotent_record 同侧）。
 * 只追加：汇率快照与两张凭证号落库后不可篡改。
 */
@Mapper
public interface FxExchangeMapper {

    @Insert("INSERT INTO fx_exchange(biz_no,from_biz_no,to_biz_no,from_account_id,to_account_id,from_currency,to_currency,"
            + "amount,fee,fee_bps,gross_to_amount,net_to_amount,fx_gain,fx_gain_account,fee_account,"
            + "buy_rate,sell_rate,mid_rate,rate_scale,rate_version,rate_source,rate_effective_at,"
            + "from_balance_after,to_balance_after,operator,created_at) "
            + "VALUES(#{bizNo},#{fromBizNo},#{toBizNo},#{fromAccountId},#{toAccountId},#{fromCurrency},#{toCurrency},"
            + "#{amount},#{fee},#{feeBps},#{grossToAmount},#{netToAmount},#{fxGain},#{fxGainAccount},#{feeAccount},"
            + "#{buyRate},#{sellRate},#{midRate},#{rateScale},#{rateVersion},#{rateSource},#{rateEffectiveAt},"
            + "#{fromBalanceAfter},#{toBalanceAfter},#{operator},#{createdAt})")
    int insert(FxExchangeDO exchange);

    @Select("SELECT * FROM fx_exchange WHERE biz_no=#{bizNo}")
    FxExchangeDO selectByBizNo(String bizNo);
}