package com.qoder.payment.member.mapper;

import com.qoder.payment.member.dataobject.ReviewTaskDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ReviewTaskMapper {

    @Select("SELECT next_value_for('seq_review_task')")
    long nextId();

    @Insert("INSERT INTO review_task(id,application_id,reviewer,status,opinion,created_at,reviewed_at) "
            + "VALUES(#{id},#{applicationId},#{reviewer},#{status},#{opinion},#{createdAt},#{reviewedAt})")
    int insert(ReviewTaskDO task);

    @Select("SELECT * FROM review_task WHERE application_id=#{applicationId} AND status='PENDING'")
    ReviewTaskDO selectPendingByApplication(String applicationId);

    @Select("SELECT * FROM review_task ORDER BY created_at DESC, id DESC")
    List<ReviewTaskDO> selectAll();

    @Select("SELECT * FROM review_task WHERE status=#{status} ORDER BY created_at DESC, id DESC")
    List<ReviewTaskDO> selectByStatus(String status);

    @Select("SELECT * FROM review_task WHERE application_id=#{applicationId} ORDER BY created_at, id")
    List<ReviewTaskDO> selectByApplication(String applicationId);

    @Update("UPDATE review_task SET reviewer=#{reviewer},status=#{status},opinion=#{opinion},"
            + "reviewed_at=#{reviewedAt} WHERE id=#{id} AND status='PENDING'")
    int complete(ReviewTaskDO task);
}