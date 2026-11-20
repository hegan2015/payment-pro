package com.qoder.payment.marketing.mapper;

import com.qoder.payment.facade.marketing.CouponUseDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** X-3a 红包核销单（kind=RED_PACKET，voucher_id=red_packet_id 别名映射）。 */
@Mapper
public interface RedPacketUseMapper {

    String COLS = "id, red_packet_id AS voucher_id, 'RED_PACKET' AS kind, payment_id, order_id, member_id, "
            + "account_id, amount, biz_no, status, request_id, operator, created_at, updated_at";

    @Insert("INSERT INTO red_packet_use(id,red_packet_id,payment_id,order_id,member_id,account_id,amount,"
            + "biz_no,status,request_id,operator,created_at,updated_at) VALUES(#{id},#{voucherId},#{paymentId},"
            + "#{orderId},#{memberId},#{accountId},#{amount},#{bizNo},#{status},#{requestId},#{operator},"
            + "#{createdAt},#{updatedAt})")
    int insert(CouponUseDTO use);

    @Select("SELECT " + COLS + " FROM red_packet_use WHERE id=#{id}")
    CouponUseDTO selectById(String id);

    @Select("SELECT " + COLS + " FROM red_packet_use WHERE red_packet_id=#{redPacketId}")
    CouponUseDTO selectByRedPacket(String redPacketId);

    @Select("SELECT " + COLS + " FROM red_packet_use WHERE request_id=#{requestId}")
    CouponUseDTO selectByRequest(String requestId);

    @Select("SELECT " + COLS + " FROM red_packet_use WHERE payment_id=#{paymentId} ORDER BY created_at, id")
    List<CouponUseDTO> selectByPayment(String paymentId);

    @Update("UPDATE red_packet_use SET status='REFUNDED', updated_at=#{now} WHERE id=#{id} AND status='USED'")
    int casRefund(@Param("id") String id, @Param("now") long now);

    @Select("SELECT COALESCE(SUM(amount),0) FROM red_packet_use WHERE status=#{status}")
    long sumByStatus(String status);

    @Select("SELECT COALESCE(SUM(amount),0) FROM red_packet_use WHERE payment_id=#{paymentId} AND status=#{status}")
    long sumByPayment(@Param("paymentId") String paymentId, @Param("status") String status);
}