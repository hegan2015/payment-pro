package com.qoder.payment.settle.mapper;

import com.qoder.payment.settle.dataobject.SettleRecordDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SettleRecordMapper {

    @Select("SELECT NEXT VALUE FOR seq_settle")
    long nextId();

    @Insert("INSERT INTO settle_record(id,type,biz_id,payment_id,order_id,merchant_id,gross,fee,net,method,status,created_at) "
            + "VALUES(#{id},#{type},#{bizId},#{paymentId},#{orderId},#{merchantId},#{gross},#{fee},#{net},#{method},#{status},#{createdAt})")
    int insert(SettleRecordDO record);

    @Select("SELECT * FROM settle_record WHERE biz_id=#{bizId} AND type=#{type}")
    SettleRecordDO selectByBiz(@Param("bizId") String bizId, @Param("type") String type);

    @Select("SELECT * FROM settle_record ORDER BY created_at DESC, id DESC")
    List<SettleRecordDO> selectAll();

    @Select("SELECT * FROM settle_record WHERE merchant_id=#{merchantId} ORDER BY created_at DESC, id DESC")
    List<SettleRecordDO> selectByMerchant(String merchantId);

    @Select("SELECT COUNT(*) FROM settle_record WHERE type='PAY'")
    int payCount();

    @Select("SELECT COALESCE(SUM(gross),0) FROM settle_record WHERE type='PAY'")
    long totalGross();

    @Select("SELECT COALESCE(SUM(CASE WHEN type='PAY' THEN fee ELSE -fee END),0) FROM settle_record")
    long totalFee();

    @Select("SELECT COALESCE(SUM(CASE WHEN type='PAY' THEN net ELSE -net END),0) FROM settle_record")
    long totalNet();

    @Select("SELECT COALESCE(SUM(gross),0) FROM settle_record WHERE type='REFUND'")
    long totalRefund();
}