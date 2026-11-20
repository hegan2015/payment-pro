package com.qoder.payment.marketing.mapper;

import com.qoder.payment.facade.marketing.ActivityUseDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** X-3b 活动核销单：UNIQUE(activity_id,payment_id) + UNIQUE(request_id) 双重幂等。 */
@Mapper
public interface ActivityUseMapper {

    @Insert("INSERT INTO activity_use(id,activity_id,payment_id,order_id,member_id,account_id,amount,order_amount,"
            + "biz_no,status,request_id,operator,created_at,updated_at) VALUES(#{id},#{activityId},#{paymentId},"
            + "#{orderId},#{memberId},#{accountId},#{amount},#{orderAmount},#{bizNo},#{status},#{requestId},"
            + "#{operator},#{createdAt},#{updatedAt})")
    int insert(ActivityUseDTO use);

    @Select("SELECT * FROM activity_use WHERE id=#{id}")
    ActivityUseDTO selectById(String id);

    @Select("SELECT * FROM activity_use WHERE activity_id=#{activityId} AND payment_id=#{paymentId}")
    ActivityUseDTO selectByKey(@Param("activityId") String activityId, @Param("paymentId") String paymentId);

    @Select("SELECT * FROM activity_use WHERE request_id=#{requestId}")
    ActivityUseDTO selectByRequest(String requestId);

    @Select("SELECT * FROM activity_use WHERE payment_id=#{paymentId} ORDER BY created_at, id")
    List<ActivityUseDTO> selectByPayment(String paymentId);

    @Update("UPDATE activity_use SET status='REFUNDED', updated_at=#{now} WHERE id=#{id} AND status='USED'")
    int casRefund(@Param("id") String id, @Param("now") long now);

    @Select("SELECT COALESCE(SUM(amount),0) FROM activity_use WHERE status=#{status}")
    long sumByStatus(String status);

    @Select("SELECT COALESCE(SUM(amount),0) FROM activity_use WHERE payment_id=#{paymentId} AND status=#{status}")
    long sumByPayment(@Param("paymentId") String paymentId, @Param("status") String status);
}