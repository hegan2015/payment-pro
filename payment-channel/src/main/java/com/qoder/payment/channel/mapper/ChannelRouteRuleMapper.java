package com.qoder.payment.channel.mapper;

import com.qoder.payment.channel.dataobject.ChannelRouteRuleDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** CH-3 路由规则（版本化，priority 越小越优先）。 */
@Mapper
public interface ChannelRouteRuleMapper {

    @Insert("INSERT INTO channel_route_rule(priority,min_amount,max_amount,card_bin,channel_code,weight,status,version,created_at) "
            + "VALUES(#{priority},#{minAmount},#{maxAmount},#{cardBin},#{channelCode},#{weight},#{status},#{version},#{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ChannelRouteRuleDO rule);

    @Select("SELECT * FROM channel_route_rule WHERE status='ENABLED' ORDER BY priority ASC, id ASC")
    List<ChannelRouteRuleDO> selectEnabled();

    @Select("SELECT * FROM channel_route_rule ORDER BY priority ASC, id ASC")
    List<ChannelRouteRuleDO> selectAll();
}