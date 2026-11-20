package com.qoder.payment.trade.mapper;

import com.qoder.payment.trade.dataobject.OrderPriceLogDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** T-6 改价留痕（单库不分片：request_id UNIQUE 全局幂等）。 */
@Mapper
public interface OrderPriceLogMapper {

    @Select("SELECT next_value_for('seq_price_log')")
    long nextId();

    @Insert("INSERT INTO order_price_log(id,order_id,old_amount,new_amount,delta,direction,reason,operator,request_id,refund_id,supplement_order_id,created_at) "
            + "VALUES(#{id},#{orderId},#{oldAmount},#{newAmount},#{delta},#{direction},#{reason},#{operator},#{requestId},#{refundId},#{supplementOrderId},#{createdAt})")
    int insert(OrderPriceLogDO log);

    @Select("SELECT * FROM order_price_log WHERE order_id=#{orderId} ORDER BY created_at, id")
    List<OrderPriceLogDO> selectByOrder(String orderId);

    @Select("SELECT * FROM order_price_log WHERE request_id=#{requestId}")
    OrderPriceLogDO selectByRequest(String requestId);
}