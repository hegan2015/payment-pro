package com.qoder.payment.trade.mapper;

import com.qoder.payment.trade.dataobject.OrderDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface OrderMapper {

    @Select("SELECT next_value_for('seq_order')")
    long nextId();

    @Insert("INSERT INTO trade_order(id,merchant_id,merchant_name,out_trade_no,biz_type,subject,amount,status,payment_id,refunded_amount,created_at,paid_at,closed_at) "
            + "VALUES(#{id},#{merchantId},#{merchantName},#{outTradeNo},#{bizType},#{subject},#{amount},#{status},#{paymentId},#{refundedAmount},#{createdAt},#{paidAt},#{closedAt})")
    int insert(OrderDO order);

    @Select("SELECT * FROM trade_order WHERE id=#{id}")
    OrderDO selectById(String id);

    @Select("SELECT * FROM trade_order WHERE merchant_id=#{merchantId} AND out_trade_no=#{outTradeNo}")
    OrderDO selectByOutTradeNo(@Param("merchantId") String merchantId, @Param("outTradeNo") String outTradeNo);

    @Select("SELECT * FROM trade_order ORDER BY created_at DESC, id DESC")
    List<OrderDO> selectAll();

    @Update("UPDATE trade_order SET status='PAYING', payment_id=#{paymentId} WHERE id=#{id} AND status='CREATED'")
    int reserve(@Param("id") String id, @Param("paymentId") String paymentId);

    @Update("UPDATE trade_order SET status='PAID', paid_at=#{paidAt} WHERE id=#{id} AND status='PAYING'")
    int markPaid(@Param("id") String id, @Param("paidAt") long paidAt);

    @Update("UPDATE trade_order SET status='CREATED', payment_id=NULL WHERE id=#{id} AND status='PAYING' AND payment_id=#{paymentId}")
    int release(@Param("id") String id, @Param("paymentId") String paymentId);

    @Update("UPDATE trade_order SET status='CLOSED', closed_at=#{closedAt} WHERE id=#{id} AND status='CREATED'")
    int close(@Param("id") String id, @Param("closedAt") long closedAt);

    @Update("UPDATE trade_order SET refunded_amount=#{refundedAmount}, status=#{status} WHERE id=#{id}")
    int updateRefund(@Param("id") String id, @Param("refundedAmount") long refundedAmount, @Param("status") String status);
}