package com.qoder.payment.marketing.mapper;

import com.qoder.payment.facade.marketing.CouponUseDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * X-3a 券核销单。UNIQUE(coupon_id) 保证一券只核销一次；UNIQUE(request_id) 保证调用方幂等键不重。
 * 出参统一 {@link CouponUseDTO}（kind=COUPON，voucher_id=coupon_id 别名映射）。
 */
@Mapper
public interface CouponUseMapper {

    String COLS = "id, coupon_id AS voucher_id, 'COUPON' AS kind, payment_id, order_id, member_id, account_id, "
            + "amount, biz_no, status, request_id, operator, created_at, updated_at";

    @Insert("INSERT INTO coupon_use(id,coupon_id,payment_id,order_id,member_id,account_id,amount,biz_no,"
            + "status,request_id,operator,created_at,updated_at) VALUES(#{id},#{voucherId},#{paymentId},"
            + "#{orderId},#{memberId},#{accountId},#{amount},#{bizNo},#{status},#{requestId},#{operator},"
            + "#{createdAt},#{updatedAt})")
    int insert(CouponUseDTO use);

    @Select("SELECT " + COLS + " FROM coupon_use WHERE id=#{id}")
    CouponUseDTO selectById(String id);

    @Select("SELECT " + COLS + " FROM coupon_use WHERE coupon_id=#{couponId}")
    CouponUseDTO selectByCoupon(String couponId);

    @Select("SELECT " + COLS + " FROM coupon_use WHERE request_id=#{requestId}")
    CouponUseDTO selectByRequest(String requestId);

    @Select("SELECT " + COLS + " FROM coupon_use WHERE payment_id=#{paymentId} ORDER BY created_at, id")
    List<CouponUseDTO> selectByPayment(String paymentId);

    /** 退回 CAS：USED → REFUNDED，只成功一次。 */
    @Update("UPDATE coupon_use SET status='REFUNDED', updated_at=#{now} WHERE id=#{id} AND status='USED'")
    int casRefund(@Param("id") String id, @Param("now") long now);

    @Select("SELECT COALESCE(SUM(amount),0) FROM coupon_use WHERE status=#{status}")
    long sumByStatus(String status);

    @Select("SELECT COALESCE(SUM(amount),0) FROM coupon_use WHERE payment_id=#{paymentId} AND status=#{status}")
    long sumByPayment(@Param("paymentId") String paymentId, @Param("status") String status);
}