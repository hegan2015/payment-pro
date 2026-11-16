package com.qoder.payment.payment.mapper;

import com.qoder.payment.payment.dataobject.RefundDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RefundMapper {

    @Select("SELECT NEXT VALUE FOR seq_refund")
    long nextId();

    @Insert("INSERT INTO refund_record(id,payment_id,amount,fee_refund,request_id,xid,status,biz_no,created_at) "
            + "VALUES(#{id},#{paymentId},#{amount},#{feeRefund},#{requestId},#{xid},#{status},#{bizNo},#{createdAt})")
    int insert(RefundDO refund);

    @Select("SELECT * FROM refund_record WHERE id=#{id}")
    RefundDO selectById(String id);

    @Select("SELECT * FROM refund_record WHERE request_id=#{requestId}")
    RefundDO selectByRequestId(String requestId);

    @Select("SELECT * FROM refund_record WHERE xid=#{xid}")
    RefundDO selectByXid(String xid);

    @Update("UPDATE refund_record SET xid=#{xid} WHERE id=#{id}")
    int updateXid(@Param("id") String id, @Param("xid") String xid);

    @Update("UPDATE refund_record SET status=#{status} WHERE id=#{id} AND status='PROCESSING'")
    int finish(@Param("id") String id, @Param("status") String status);
}