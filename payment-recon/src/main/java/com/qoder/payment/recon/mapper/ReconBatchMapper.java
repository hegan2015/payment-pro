package com.qoder.payment.recon.mapper;

import com.qoder.payment.recon.dataobject.ReconBatchDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** RC-2/RC-3 对账批次（SINGLE 表；幂等载体：同会计日+类型+渠道+分片唯一，重跑复用行）。 */
@Mapper
public interface ReconBatchMapper {

    @Select("SELECT next_value_for('seq_recon_batch')")
    long nextId();

    @Insert("INSERT INTO recon_batch(id,biz_date,recon_type,channel_code,shard_index,shard_total,status,attempt,"
            + "report_id,total,checked,passed,failed,dispute_count,message,created_at,updated_at) "
            + "VALUES(#{id},#{bizDate},#{reconType},#{channelCode},#{shardIndex},#{shardTotal},#{status},#{attempt},"
            + "#{reportId,jdbcType=VARCHAR},#{total},#{checked},#{passed},#{failed},#{disputeCount},"
            + "#{message,jdbcType=VARCHAR},#{createdAt},#{updatedAt})")
    int insert(ReconBatchDO batch);

    @Select("SELECT * FROM recon_batch WHERE biz_date=#{bizDate} AND recon_type=#{reconType} "
            + "AND channel_code=#{channelCode} AND shard_index=#{shardIndex} AND shard_total=#{shardTotal}")
    ReconBatchDO selectUnique(@Param("bizDate") long bizDate, @Param("reconType") String reconType,
                              @Param("channelCode") String channelCode,
                              @Param("shardIndex") int shardIndex, @Param("shardTotal") int shardTotal);

    /** 重跑开跑：attempt 递增、状态回 RUNNING（终态可重跑，幂等续跑）。 */
    @Update("UPDATE recon_batch SET status='RUNNING', attempt=attempt+1, message=#{message,jdbcType=VARCHAR}, "
            + "updated_at=#{now} WHERE id=#{id}")
    int markRunning(@Param("id") String id, @Param("message") String message, @Param("now") long now);

    @Update("UPDATE recon_batch SET status=#{status}, report_id=#{reportId,jdbcType=VARCHAR}, total=#{total}, "
            + "checked=#{checked}, passed=#{passed}, failed=#{failed}, dispute_count=#{disputeCount}, "
            + "message=#{message,jdbcType=VARCHAR}, updated_at=#{now} WHERE id=#{id}")
    int finish(@Param("id") String id, @Param("status") String status, @Param("reportId") String reportId,
               @Param("total") int total, @Param("checked") int checked, @Param("passed") int passed,
               @Param("failed") int failed, @Param("disputeCount") int disputeCount,
               @Param("message") String message, @Param("now") long now);

    @Select("<script>SELECT * FROM recon_batch "
            + "<where>"
            + "<if test='bizDate != null'>AND biz_date=#{bizDate} </if>"
            + "<if test='reconType != null and reconType != \"\"'>AND recon_type=#{reconType} </if>"
            + "</where>"
            + " ORDER BY biz_date DESC, recon_type, channel_code, shard_index</script>")
    List<ReconBatchDO> selectList(@Param("bizDate") Long bizDate, @Param("reconType") String reconType);
}