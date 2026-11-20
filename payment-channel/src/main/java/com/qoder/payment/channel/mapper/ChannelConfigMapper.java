package com.qoder.payment.channel.mapper;

import com.qoder.payment.channel.dataobject.ChannelConfigDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** CH-2a/CH-5 渠道配置：只增不改既有业务数据；配置行由启动 seeding 幂等插入。 */
@Mapper
public interface ChannelConfigMapper {

    @Insert("INSERT INTO channel_config(channel_code,name,adapter_type,status,env,fee_bps,weight,"
            + "single_limit,daily_limit,maintenance_start,maintenance_end,config,created_at,updated_at) "
            + "VALUES(#{channelCode},#{name},#{adapterType},#{status},#{env},#{feeBps},#{weight},"
            + "#{singleLimit},#{dailyLimit},#{maintenanceStart},#{maintenanceEnd},#{config},#{createdAt},#{updatedAt})")
    int insert(ChannelConfigDO config);

    @Select("SELECT * FROM channel_config WHERE channel_code=#{channelCode}")
    ChannelConfigDO selectByCode(String channelCode);

    @Select("SELECT * FROM channel_config ORDER BY channel_code ASC")
    List<ChannelConfigDO> selectAll();

    @Update("UPDATE channel_config SET single_limit=#{singleLimit}, daily_limit=#{dailyLimit}, updated_at=#{now} "
            + "WHERE channel_code=#{channelCode}")
    int updateLimits(@Param("channelCode") String channelCode, @Param("singleLimit") Long singleLimit,
                     @Param("dailyLimit") Long dailyLimit, @Param("now") long now);

    @Update("UPDATE channel_config SET maintenance_start=#{start}, maintenance_end=#{end}, updated_at=#{now} "
            + "WHERE channel_code=#{channelCode}")
    int updateMaintenance(@Param("channelCode") String channelCode, @Param("start") Long start,
                          @Param("end") Long end, @Param("now") long now);

    @Update("UPDATE channel_config SET status=#{status}, updated_at=#{now} WHERE channel_code=#{channelCode}")
    int updateStatus(@Param("channelCode") String channelCode, @Param("status") String status, @Param("now") long now);

    @Update("UPDATE channel_config SET weight=#{weight}, fee_bps=#{feeBps}, updated_at=#{now} WHERE channel_code=#{channelCode}")
    int updateScoreFactors(@Param("channelCode") String channelCode, @Param("weight") Integer weight,
                           @Param("feeBps") Integer feeBps, @Param("now") long now);
}