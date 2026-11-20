package com.qoder.payment.trade.mapper;

import com.qoder.payment.trade.dataobject.TradeSystemAccountDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** T-1 系统担保户登记（单库不分片；name PK 即跨实例占位闸门）。 */
@Mapper
public interface TradeSystemAccountMapper {

    @Select("SELECT * FROM trade_system_account WHERE name=#{name}")
    TradeSystemAccountDO selectByName(@Param("name") String name);

    @Insert("INSERT INTO trade_system_account(name,member_id,account_id,created_at,updated_at) "
            + "VALUES(#{name},NULL,NULL,#{createdAt},#{createdAt})")
    int insertPlaceholder(@Param("name") String name, @Param("createdAt") long createdAt);

    /** 只由占位者补全一次（account_id IS NULL 守卫）：并发下后到者读胜者，绝不同户双写。 */
    @Update("UPDATE trade_system_account SET member_id=#{memberId}, account_id=#{accountId}, updated_at=#{now} "
            + "WHERE name=#{name} AND account_id IS NULL")
    int complete(@Param("name") String name, @Param("memberId") String memberId,
                 @Param("accountId") String accountId, @Param("now") long now);
}