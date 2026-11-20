package com.qoder.payment.settle.mapper;

import com.qoder.payment.facade.settle.AdvanceRecordDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** ST-6 垫资/追偿单据（单库不分片：request_id 全局幂等键 + 垫资单 CAS 状态机）。 */
@Mapper
public interface AdvanceMapper {

    @Insert("INSERT INTO advance_record(id,merchant_id,account_id,type,ref_id,amount,recovered_amount,status,"
            + "biz_no,request_id,operator,reason,created_at,updated_at) "
            + "VALUES(#{id},#{merchantId},#{accountId},#{type},#{refId},#{amount},#{recoveredAmount},#{status},"
            + "#{bizNo},#{requestId},#{operator},#{reason},#{createdAt},#{updatedAt})")
    int insert(AdvanceRecordDTO record);

    @Select("SELECT * FROM advance_record WHERE id=#{id}")
    AdvanceRecordDTO selectById(String id);

    @Select("SELECT * FROM advance_record WHERE request_id=#{requestId}")
    AdvanceRecordDTO selectByRequestId(String requestId);

    @Select("SELECT * FROM advance_record WHERE merchant_id=#{merchantId} ORDER BY created_at, id")
    List<AdvanceRecordDTO> selectByMerchant(String merchantId);

    @Select("SELECT * FROM advance_record ORDER BY created_at, id")
    List<AdvanceRecordDTO> selectAll();

    @Select("SELECT SUM(amount) FROM advance_record WHERE merchant_id=#{merchantId} AND type=#{type}")
    Long sumAmount(@Param("merchantId") String merchantId, @Param("type") String type);

    @Select("SELECT SUM(amount) FROM advance_record WHERE type=#{type}")
    Long sumAmountGlobal(String type);

    /** 垫资单追偿进度 CAS（乐观并发，超额追偿一律拒绝）。 */
    @Update("UPDATE advance_record SET recovered_amount=#{newRecovered},status=#{status},updated_at=#{updatedAt} "
            + "WHERE id=#{id} AND recovered_amount=#{expRecovered}")
    int casRecover(@Param("id") String id, @Param("expRecovered") long expRecovered,
                   @Param("newRecovered") long newRecovered, @Param("status") String status,
                   @Param("updatedAt") long updatedAt);
}