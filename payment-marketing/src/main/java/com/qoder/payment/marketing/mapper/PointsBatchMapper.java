package com.qoder.payment.marketing.mapper;

import com.qoder.payment.marketing.dataobject.PointsBatchDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** X-3b 积分批次：FIFO（先到期先消耗）+ 按批过期，remaining 原子扣减不为负。 */
@Mapper
public interface PointsBatchMapper {

    @Insert("INSERT INTO points_batch(id,member_id,amount,remaining,expire_at,status,biz_no,created_at,updated_at) "
            + "VALUES(#{id},#{memberId},#{amount},#{remaining},#{expireAt},#{status},#{bizNo},#{createdAt},#{updatedAt})")
    int insert(PointsBatchDO batch);

    @Select("SELECT * FROM points_batch WHERE id=#{id}")
    PointsBatchDO selectById(String id);

    @Select("SELECT * FROM points_batch WHERE biz_no=#{bizNo}")
    PointsBatchDO selectByBiz(String bizNo);

    @Select("SELECT * FROM points_batch WHERE member_id=#{memberId} AND status='ACTIVE' AND remaining>0 "
            + "AND expire_at>#{now} ORDER BY expire_at ASC, id ASC")
    List<PointsBatchDO> selectFifo(@Param("memberId") String memberId, @Param("now") long now);

    @Select("SELECT * FROM points_batch WHERE member_id=#{memberId} AND status='ACTIVE' AND remaining>0 "
            + "AND expire_at>#{now} ORDER BY expire_at ASC, id ASC FOR UPDATE")
    List<PointsBatchDO> selectFifoForUpdate(@Param("memberId") String memberId, @Param("now") long now);

    @Update("UPDATE points_batch SET remaining=remaining-#{n}, updated_at=#{now} "
            + "WHERE id=#{id} AND status='ACTIVE' AND remaining>=#{n}")
    int consume(@Param("id") String id, @Param("n") long n, @Param("now") long now);

    @Update("UPDATE points_batch SET status='EXHAUSTED', updated_at=#{now} WHERE id=#{id} AND remaining=0")
    int markExhausted(@Param("id") String id, @Param("now") long now);

    /** 过期终态标记（remaining 已扣至 0 才置 EXPIRED，冻结部分不静默蒸发）。 */
    @Update("UPDATE points_batch SET status='EXPIRED', updated_at=#{now} WHERE id=#{id} AND remaining=0")
    int markExpired(@Param("id") String id, @Param("now") long now);

    @Select("SELECT * FROM points_batch WHERE status='ACTIVE' AND remaining>0 AND expire_at<=#{now} "
            + "ORDER BY expire_at ASC, id ASC")
    List<PointsBatchDO> selectExpired(@Param("now") long now);

    /** 过期 CAS：ACTIVE 且有余额才清零（幂等，重复执行命中 0 行）。 */
    @Update("UPDATE points_batch SET remaining=0, status='EXPIRED', updated_at=#{now} "
            + "WHERE id=#{id} AND status='ACTIVE' AND remaining>0")
    int expireBatch(@Param("id") String id, @Param("now") long now);

    @Select("SELECT COALESCE(SUM(remaining),0) FROM points_batch WHERE member_id=#{memberId} AND status='ACTIVE'")
    long sumRemaining(String memberId);
}