package com.qoder.payment.payment.mapper;

import com.qoder.payment.payment.dataobject.ShareDetailDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ShareDetailMapper {

    @Insert("INSERT INTO share_detail(id,share_id,receiver_account_id,amount,status,biz_no,created_at) "
            + "VALUES(#{id},#{shareId},#{receiverAccountId},#{amount},#{status},#{bizNo},#{createdAt})")
    int insert(ShareDetailDO detail);

    @Select("SELECT * FROM share_detail WHERE share_id=#{shareId} ORDER BY id ASC")
    List<ShareDetailDO> selectByShareId(String shareId);

    @Select("SELECT COUNT(*) FROM share_detail WHERE share_id=#{shareId} AND status=#{status}")
    int countByStatus(@Param("shareId") String shareId, @Param("status") String status);

    @Update("UPDATE share_detail SET status='SHARED' WHERE share_id=#{shareId} AND receiver_account_id=#{receiver} AND status='PENDING'")
    int markShared(@Param("shareId") String shareId, @Param("receiver") String receiver);

    @Update("UPDATE share_detail SET status='BACKED' WHERE share_id=#{shareId} AND receiver_account_id=#{receiver} AND status='SHARED'")
    int markBacked(@Param("shareId") String shareId, @Param("receiver") String receiver);
}