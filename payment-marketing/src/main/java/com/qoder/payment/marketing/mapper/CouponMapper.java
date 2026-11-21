package com.qoder.payment.marketing.mapper;

import com.qoder.payment.facade.marketing.CouponDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * X-3a 优惠券。状态机 AVAILABLE → USED → REFUNDED（终态）/ AVAILABLE → EXPIRED。
 *
 * <p>所有状态迁移都是<b>带前置状态的条件 UPDATE（CAS）</b>，返回受影响行数：
 * 并发/重放只有一次能成功，配合 coupon_use.coupon_id UNIQUE 与幂等表三重防重核销。
 */
@Mapper
public interface CouponMapper {

    @Insert("INSERT INTO coupon(id,member_id,template_id,amount,threshold,status,expire_at,request_id,"
            + "biz_no,use_id,operator,created_at,updated_at) VALUES(#{id},#{memberId},#{templateId},#{amount},"
            + "#{threshold},#{status},#{expireAt},#{requestId},#{bizNo},#{useId},#{operator},#{createdAt},#{updatedAt})")
    int insert(CouponDTO coupon);

    @Select("SELECT * FROM coupon WHERE id=#{id}")
    CouponDTO selectById(String id);

    @Select("SELECT * FROM coupon WHERE request_id=#{requestId}")
    CouponDTO selectByRequest(String requestId);

    @Select("<script>SELECT * FROM coupon WHERE member_id=#{memberId}"
            + "<if test='status != null and status != \"\"'> AND status=#{status}</if>"
            + " ORDER BY created_at DESC, id DESC</script>")
    List<CouponDTO> selectByMember(@Param("memberId") String memberId, @Param("status") String status);

    /** 可用券（未过期 + 门槛命中）；orderAmount 为 null 表示不做门槛过滤。 */
    @Select("<script>SELECT * FROM coupon WHERE member_id=#{memberId} AND status='AVAILABLE' AND expire_at&gt;#{now}"
            + "<if test='orderAmount != null'> AND threshold&lt;=#{orderAmount}</if>"
            + " ORDER BY expire_at ASC, id ASC</script>")
    List<CouponDTO> selectAvailable(@Param("memberId") String memberId, @Param("now") long now,
                                    @Param("orderAmount") Long orderAmount);

    /** 核销 CAS：AVAILABLE → USED，只成功一次。 */
    @Update("UPDATE coupon SET status='USED', use_id=#{useId}, updated_at=#{now} "
            + "WHERE id=#{id} AND status='AVAILABLE'")
    int casUse(@Param("id") String id, @Param("useId") String useId, @Param("now") long now);

    /** TCC Try 预占 CAS：AVAILABLE → FROZEN。 */
    @Update("UPDATE coupon SET status='FROZEN', updated_at=#{now} WHERE id=#{id} AND status='AVAILABLE'")
    int casReserve(@Param("id") String id, @Param("now") long now);

    /** TCC Confirm 核销 CAS：FROZEN → USED。 */
    @Update("UPDATE coupon SET status='USED', use_id=#{useId}, updated_at=#{now} "
            + "WHERE id=#{id} AND status='FROZEN'")
    int casCommit(@Param("id") String id, @Param("useId") String useId, @Param("now") long now);

    /** TCC Cancel 释放 CAS：FROZEN → AVAILABLE。 */
    @Update("UPDATE coupon SET status='AVAILABLE', updated_at=#{now} WHERE id=#{id} AND status='FROZEN'")
    int casRelease(@Param("id") String id, @Param("now") long now);

    /** 退回 CAS：USED → REFUNDED（终态，券作废；重新可用需重新发放，保 coupon_use.coupon_id UNIQUE 语义）。 */
    @Update("UPDATE coupon SET status='REFUNDED', updated_at=#{now} WHERE id=#{id} AND status='USED'")
    int casRefund(@Param("id") String id, @Param("now") long now);

    /** 过期作业：AVAILABLE 且已到期 → EXPIRED（幂等，重复执行命中 0 行）。 */
    @Update("UPDATE coupon SET status='EXPIRED', updated_at=#{now} "
            + "WHERE status='AVAILABLE' AND expire_at<=#{now}")
    int expireDue(@Param("now") long now);

    @Select("SELECT COUNT(*) FROM coupon WHERE member_id=#{memberId} AND status=#{status}")
    int countByStatus(@Param("memberId") String memberId, @Param("status") String status);
}