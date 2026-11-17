package com.qoder.payment.payment.mapper;

import com.qoder.payment.payment.dataobject.PaymentDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PaymentMapper {

    @Select("SELECT NEXT VALUE FOR seq_payment")
    long nextId();

    @Insert("INSERT INTO payment(id,order_id,merchant_id,biz_type,method,payer_account_id,card_no,merchant_account_id,amount,fee,status,"
            + "refunded_amount,refunded_fee,channel_biz_no,fail_reason,request_id,xid,callback_url,created_at) "
            + "VALUES(#{id},#{orderId},#{merchantId},#{bizType},#{method},#{payerAccountId},#{cardNo},#{merchantAccountId},#{amount},#{fee},#{status},"
            + "#{refundedAmount},#{refundedFee},#{channelBizNo},#{failReason},#{requestId},#{xid},#{callbackUrl},#{createdAt})")
    int insert(PaymentDO payment);

    @Select("SELECT * FROM payment WHERE id=#{id}")
    PaymentDO selectById(String id);

    @Select("SELECT * FROM payment WHERE request_id=#{requestId}")
    PaymentDO selectByRequestId(String requestId);

    @Select("SELECT * FROM payment WHERE xid=#{xid}")
    PaymentDO selectByXid(String xid);

    @Select("SELECT * FROM payment ORDER BY created_at DESC, id DESC")
    List<PaymentDO> selectAll();

    @Update("UPDATE payment SET xid=#{xid} WHERE id=#{id}")
    int updateXid(@Param("id") String id, @Param("xid") String xid);

    @Update("UPDATE payment SET status=#{status}, fail_reason=#{failReason} WHERE id=#{id} AND status='PROCESSING'")
    int finish(@Param("id") String id, @Param("status") String status, @Param("failReason") String failReason);

    @Update("UPDATE payment SET refunded_amount=#{refundedAmount}, refunded_fee=#{refundedFee}, "
            + "status = CASE WHEN #{refundedAmount} >= amount THEN 'REFUNDED' ELSE status END WHERE id=#{id}")
    int updateRefunded(@Param("id") String id, @Param("refundedAmount") long refundedAmount, @Param("refundedFee") long refundedFee);
}