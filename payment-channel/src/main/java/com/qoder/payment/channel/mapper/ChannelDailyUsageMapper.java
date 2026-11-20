package com.qoder.payment.channel.mapper;

import com.qoder.payment.channel.dataobject.ChannelDailyUsageDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * CH-5 渠道日累计：原子 incr（DB 行级原子更新，双 profile 同构、重启不丢，
 * 强于「local 内存」方案；不引入 Redis 依赖）。跨日按 biz_date 新行自然重置。
 */
@Mapper
public interface ChannelDailyUsageMapper {

    @Insert("INSERT INTO channel_daily_usage(channel_code,biz_date,used_amount,used_count) "
            + "VALUES(#{channelCode},#{bizDate},#{usedAmount},#{usedCount})")
    int insert(ChannelDailyUsageDO usage);

    @Select("SELECT * FROM channel_daily_usage WHERE channel_code=#{channelCode} AND biz_date=#{bizDate}")
    ChannelDailyUsageDO select(@Param("channelCode") String channelCode, @Param("bizDate") String bizDate);

    /** 原子累计：并发受理不丢计数（行锁保证 used_amount 单调增）。 */
    @Update("UPDATE channel_daily_usage SET used_amount=used_amount+#{amount}, used_count=used_count+1 "
            + "WHERE channel_code=#{channelCode} AND biz_date=#{bizDate}")
    int incr(@Param("channelCode") String channelCode, @Param("bizDate") String bizDate, @Param("amount") long amount);
}