package com.qoder.payment.payment.mapper;

import com.qoder.payment.payment.dataobject.ReviewDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** S-7 大额双人复核单：UNIQUE(biz_type,biz_no) 幂等；状态翻转只经 PENDING 的 CAS。 */
@Mapper
public interface ReviewMapper {

    @Select("SELECT next_value_for('seq_review')")
    long nextId();

    @Insert("INSERT INTO amount_review(id,biz_type,biz_no,amount,status,submitter,approver,reject_reason,created_at,reviewed_at) "
            + "VALUES(#{id},#{bizType},#{bizNo},#{amount},#{status},#{submitter},#{approver},#{rejectReason},#{createdAt},#{reviewedAt})")
    int insert(ReviewDO review);

    @Select("SELECT * FROM amount_review WHERE id=#{id}")
    ReviewDO selectById(String id);

    @Select("SELECT * FROM amount_review WHERE biz_type=#{bizType} AND biz_no=#{bizNo}")
    ReviewDO selectByBiz(@Param("bizType") String bizType, @Param("bizNo") String bizNo);

    @Select("<script>SELECT * FROM amount_review"
            + "<if test='status != null and status != \"\"'> WHERE status=#{status}</if>"
            + " ORDER BY created_at DESC, id DESC</script>")
    List<ReviewDO> selectByStatus(@Param("status") String status);

    /** 双人复核 CAS：仅 PENDING→APPROVED 翻一次；approver≠submitter 由服务层校验。 */
    @Update("UPDATE amount_review SET status='APPROVED', approver=#{approver}, reviewed_at=#{reviewedAt} "
            + "WHERE id=#{id} AND status='PENDING'")
    int casApprove(@Param("id") String id, @Param("approver") String approver, @Param("reviewedAt") long reviewedAt);

    @Update("UPDATE amount_review SET status='REJECTED', reject_reason=#{reason}, reviewed_at=#{reviewedAt} "
            + "WHERE id=#{id} AND status='PENDING'")
    int casReject(@Param("id") String id, @Param("reason") String reason, @Param("reviewedAt") long reviewedAt);
}