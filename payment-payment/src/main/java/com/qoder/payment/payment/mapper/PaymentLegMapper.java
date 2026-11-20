package com.qoder.payment.payment.mapper;

import com.qoder.payment.payment.dataobject.PaymentLegDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PaymentLegMapper {

    @Select("SELECT next_value_for('seq_leg')")
    long nextId();

    @Insert("INSERT INTO payment_leg(id,payment_id,leg_seq,method,payer_account_id,card_no,amount,fee,status,"
            + "channel_biz_no,channel_code,refunded_amount,refunded_fee,created_at) "
            + "VALUES(#{id},#{paymentId},#{legSeq},#{method},#{payerAccountId},#{cardNo},#{amount},#{fee},#{status},"
            + "#{channelBizNo},#{channelCode},#{refundedAmount},#{refundedFee},#{createdAt})")
    int insert(PaymentLegDO leg);

    @Select("SELECT * FROM payment_leg WHERE payment_id=#{paymentId} ORDER BY leg_seq ASC")
    List<PaymentLegDO> selectByPaymentId(String paymentId);

    /** 终态翻转（仅 PENDING 可翻，幂等）：完结监听/风控拒绝调用。 */
    @Update("UPDATE payment_leg SET status=#{status} WHERE payment_id=#{paymentId} AND status='PENDING'")
    int finishByPaymentId(@Param("paymentId") String paymentId, @Param("status") String status);

    /** 组合退款过账后累加腿已退（仅 SUCCESS 腿；金额守恒由编排侧水位填充保证）。 */
    @Update("UPDATE payment_leg SET refunded_amount=refunded_amount+#{amount}, refunded_fee=refunded_fee+#{fee} "
            + "WHERE id=#{id} AND status='SUCCESS'")
    int addRefunded(@Param("id") String id, @Param("amount") long amount, @Param("fee") long fee);
}