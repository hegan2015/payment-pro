package com.qoder.payment.trade.mapper;

import com.qoder.payment.trade.dataobject.OrderExpireDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** T-4 超时关闭留痕（单库不分片：order_id UNIQUE ⇒ 不重不漏的审计证据）。 */
@Mapper
public interface OrderExpireMapper {

    @Insert("INSERT INTO order_expire_log(id,order_id,closed_at,type,reopened_at,created_at) "
            + "VALUES(#{id},#{orderId},#{closedAt},#{type},#{reopenedAt},#{createdAt})")
    int insert(OrderExpireDO log);

    @Select("SELECT * FROM order_expire_log WHERE order_id=#{orderId}")
    OrderExpireDO selectByOrderId(@Param("orderId") String orderId);

    @Select("SELECT * FROM order_expire_log ORDER BY closed_at DESC, id DESC LIMIT #{limit}")
    List<OrderExpireDO> selectRecent(@Param("limit") int limit);

    /** P2-H 修正：留痕总量真值（recent(500) 窗口饱和后增量恒 0，断言口径需单调真值）。 */
    @Select("SELECT COUNT(*) FROM order_expire_log")
    long countAll();

    @Select("SELECT COUNT(*) FROM order_expire_log WHERE reopened_at IS NOT NULL")
    long countReopened();

    /** 竞态回滚留痕（只动本域本次新增的留痕行，绝不改既有业务行）。 */
    @Update("UPDATE order_expire_log SET reopened_at=#{reopenedAt} WHERE order_id=#{orderId} AND reopened_at IS NULL")
    int markReopened(@Param("orderId") String orderId, @Param("reopenedAt") long reopenedAt);
}