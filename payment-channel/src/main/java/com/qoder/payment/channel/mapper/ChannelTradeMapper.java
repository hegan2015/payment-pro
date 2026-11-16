package com.qoder.payment.channel.mapper;

import com.qoder.payment.channel.dataobject.ChannelTradeDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ChannelTradeMapper {

    @Insert("INSERT INTO channel_trade(biz_no,type,orig_biz_no,card_no,amount,status,reason,created_at) "
            + "VALUES(#{bizNo},#{type},#{origBizNo},#{cardNo},#{amount},#{status},#{reason},#{createdAt})")
    int insert(ChannelTradeDO trade);

    @Select("SELECT * FROM channel_trade WHERE biz_no=#{bizNo}")
    ChannelTradeDO selectByBizNo(String bizNo);

    @Select("SELECT * FROM channel_trade ORDER BY created_at DESC, biz_no DESC")
    List<ChannelTradeDO> selectAll();

    @Update("UPDATE channel_trade SET status=#{to} WHERE biz_no=#{bizNo} AND status=#{from}")
    int casStatus(@Param("bizNo") String bizNo, @Param("from") String from, @Param("to") String to);
}