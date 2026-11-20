package com.qoder.payment.settle.mapper;

import com.qoder.payment.facade.settle.SettleCycleDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** ST-1 商户结算周期（单库不分片配置表）。 */
@Mapper
public interface SettleCycleMapper {

    @Select("SELECT * FROM settle_cycle WHERE merchant_id=#{merchantId}")
    SettleCycleDTO select(String merchantId);

    @Select("SELECT * FROM settle_cycle ORDER BY merchant_id")
    List<SettleCycleDTO> selectAll();

    @Insert("INSERT INTO settle_cycle(merchant_id,cycle_days,fee_bps,status,created_at,updated_at) "
            + "VALUES(#{merchantId},#{cycleDays},#{feeBps},#{status},#{createdAt},#{updatedAt})")
    int insert(SettleCycleDTO cycle);

    @Update("UPDATE settle_cycle SET cycle_days=#{cycleDays}, fee_bps=#{feeBps}, status=#{status}, "
            + "updated_at=#{updatedAt} WHERE merchant_id=#{merchantId}")
    int update(SettleCycleDTO cycle);
}