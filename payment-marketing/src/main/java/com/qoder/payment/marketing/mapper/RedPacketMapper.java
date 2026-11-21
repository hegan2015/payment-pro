package com.qoder.payment.marketing.mapper;

import com.qoder.payment.facade.marketing.RedPacketDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** X-3a 红包（无门槛）。状态机与 CAS 语义同优惠券。 */
@Mapper
public interface RedPacketMapper {

    @Insert("INSERT INTO red_packet(id,member_id,template_id,amount,status,expire_at,request_id,biz_no,"
            + "use_id,operator,created_at,updated_at) VALUES(#{id},#{memberId},#{templateId},#{amount},"
            + "#{status},#{expireAt},#{requestId},#{bizNo},#{useId},#{operator},#{createdAt},#{updatedAt})")
    int insert(RedPacketDTO packet);

    @Select("SELECT * FROM red_packet WHERE id=#{id}")
    RedPacketDTO selectById(String id);

    @Select("SELECT * FROM red_packet WHERE request_id=#{requestId}")
    RedPacketDTO selectByRequest(String requestId);

    @Select("<script>SELECT * FROM red_packet WHERE member_id=#{memberId}"
            + "<if test='status != null and status != \"\"'> AND status=#{status}</if>"
            + " ORDER BY created_at DESC, id DESC</script>")
    List<RedPacketDTO> selectByMember(@Param("memberId") String memberId, @Param("status") String status);

    @Select("SELECT * FROM red_packet WHERE member_id=#{memberId} AND status='AVAILABLE' AND expire_at>#{now} "
            + "ORDER BY expire_at ASC, id ASC")
    List<RedPacketDTO> selectAvailable(@Param("memberId") String memberId, @Param("now") long now);

    @Update("UPDATE red_packet SET status='USED', use_id=#{useId}, updated_at=#{now} "
            + "WHERE id=#{id} AND status='AVAILABLE'")
    int casUse(@Param("id") String id, @Param("useId") String useId, @Param("now") long now);

    /** TCC Try 预占：AVAILABLE → FROZEN。 */
    @Update("UPDATE red_packet SET status='FROZEN', updated_at=#{now} WHERE id=#{id} AND status='AVAILABLE'")
    int casReserve(@Param("id") String id, @Param("now") long now);

    /** TCC Confirm 核销：FROZEN → USED。 */
    @Update("UPDATE red_packet SET status='USED', use_id=#{useId}, updated_at=#{now} "
            + "WHERE id=#{id} AND status='FROZEN'")
    int casCommit(@Param("id") String id, @Param("useId") String useId, @Param("now") long now);

    /** TCC Cancel 释放：FROZEN → AVAILABLE。 */
    @Update("UPDATE red_packet SET status='AVAILABLE', updated_at=#{now} WHERE id=#{id} AND status='FROZEN'")
    int casRelease(@Param("id") String id, @Param("now") long now);

    @Update("UPDATE red_packet SET status='REFUNDED', updated_at=#{now} WHERE id=#{id} AND status='USED'")
    int casRefund(@Param("id") String id, @Param("now") long now);

    @Update("UPDATE red_packet SET status='EXPIRED', updated_at=#{now} "
            + "WHERE status='AVAILABLE' AND expire_at<=#{now}")
    int expireDue(@Param("now") long now);

    @Select("SELECT COUNT(*) FROM red_packet WHERE member_id=#{memberId} AND status=#{status}")
    int countByStatus(@Param("memberId") String memberId, @Param("status") String status);
}