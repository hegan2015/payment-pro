package com.qoder.payment.recon.mapper;

import com.qoder.payment.recon.dataobject.ReconProgressDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** RC-4/RC-5 分片进度（SINGLE 表；聚合在 Java 做，不在 SQL 顶层藏 SUM）。 */
@Mapper
public interface ReconProgressMapper {

    @Insert("INSERT INTO recon_progress(biz_date,recon_type,shard_index,shard_total,total,checked,passed,failed,"
            + "dispute_count,status,updated_at) "
            + "VALUES(#{bizDate},#{reconType},#{shardIndex},#{shardTotal},#{total},#{checked},#{passed},#{failed},"
            + "#{disputeCount},#{status},#{updatedAt})")
    int insert(ReconProgressDO progress);

    @Select("SELECT * FROM recon_progress WHERE biz_date=#{bizDate} AND recon_type=#{reconType} "
            + "AND shard_index=#{shardIndex} AND shard_total=#{shardTotal}")
    ReconProgressDO selectUnique(@Param("bizDate") long bizDate, @Param("reconType") String reconType,
                                 @Param("shardIndex") int shardIndex, @Param("shardTotal") int shardTotal);

    @Update("UPDATE recon_progress SET total=#{total}, checked=#{checked}, passed=#{passed}, failed=#{failed}, "
            + "dispute_count=#{disputeCount}, status=#{status}, updated_at=#{updatedAt} "
            + "WHERE biz_date=#{bizDate} AND recon_type=#{reconType} AND shard_index=#{shardIndex} AND shard_total=#{shardTotal}")
    int update(ReconProgressDO progress);

    @Select("SELECT * FROM recon_progress WHERE biz_date=#{bizDate} AND recon_type=#{reconType} "
            + "ORDER BY shard_total, shard_index")
    List<ReconProgressDO> selectByDay(@Param("bizDate") long bizDate, @Param("reconType") String reconType);
}