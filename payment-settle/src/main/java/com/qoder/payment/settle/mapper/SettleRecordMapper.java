package com.qoder.payment.settle.mapper;

import com.qoder.payment.settle.dataobject.SettleRecordDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SettleRecordMapper {

    /** 单库取号（payment.sharding.enabled=false 时唯一取号路径，行为逐字节不变）。 */
    @Select("SELECT next_value_for('seq_settle')")
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

    // I-5a 跨片聚合：SUM 必须作为顶层投影，ShardingSphere 归并引擎才会「各片 SUM 再相加」。
    // 原 COALESCE(SUM(x),0) 把聚合藏在函数里会被当普通投影（只取首片值）→ 净额跨片失真。
    // 空表时 SUM 返回 NULL，由 SettleService 统一 nullToZero，单库/分片两端结果完全一致。
    @Select("SELECT SUM(gross) FROM settle_record WHERE type='PAY'")
    Long totalGross();

    @Select("SELECT SUM(CASE WHEN type='PAY' THEN fee ELSE -fee END) FROM settle_record")
    Long totalFee();

    @Select("SELECT SUM(CASE WHEN type='PAY' THEN net ELSE -net END) FROM settle_record")
    Long totalNet();

    @Select("SELECT SUM(gross) FROM settle_record WHERE type='REFUND'")
    Long totalRefund();
}