package com.qoder.payment.trade.mapper;

import com.qoder.payment.trade.dataobject.EscrowConfirmDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** T-1 担保确认收货留痕（单库不分片：含 UNIQUE(order_id) 全局幂等键，分片会破坏跨片唯一性）。 */
@Mapper
public interface EscrowConfirmMapper {

    @Insert("INSERT INTO escrow_confirm_log(id,order_id,payment_id,amount,fee,type,biz_no,hold_account_id,payee_account_id,operator,created_at) "
            + "VALUES(#{id},#{orderId},#{paymentId},#{amount},#{fee},#{type},#{bizNo},#{holdAccountId},#{payeeAccountId},#{operator},#{createdAt})")
    int insert(EscrowConfirmDO log);

    @Select("SELECT * FROM escrow_confirm_log WHERE order_id=#{orderId}")
    EscrowConfirmDO selectByOrderId(@Param("orderId") String orderId);

    @Select("SELECT * FROM escrow_confirm_log ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<EscrowConfirmDO> selectRecent(@Param("limit") int limit);

    /** P2-H 修正：确认留痕总量真值（同 order_expire_log 窗口饱和问题）。 */
    @Select("SELECT COUNT(*) FROM escrow_confirm_log")
    long countAll();

    @Select("SELECT COUNT(*) FROM escrow_confirm_log WHERE type=#{type}")
    long countByType(@Param("type") String type);
}