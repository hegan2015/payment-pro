package com.qoder.payment.accounting.mapper;

import com.qoder.payment.accounting.dataobject.BatchRunDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * AC-3 批处理状态机（<b>不分片</b>：全局作业留痕，UNIQUE 键保证同片同日至多一行）。
 * 失败重跑只 UPDATE 本行（attempt+1），绝不 DELETE 历史留痕。
 */
@Mapper
public interface BatchRunMapper {

    @Insert("INSERT INTO accounting_batch_run(id,batch_type,biz_date,shard_index,shard_total,status,step,"
            + "attempt,error,started_at,finished_at) "
            + "VALUES(#{id},#{batchType},#{bizDate},#{shardIndex},#{shardTotal},#{status},#{step},"
            + "#{attempt},#{error},#{startedAt},#{finishedAt})")
    int insert(BatchRunDO run);

    @Update("UPDATE accounting_batch_run SET status=#{status},step=#{step},attempt=#{attempt},error=#{error},"
            + "started_at=#{startedAt},finished_at=#{finishedAt} WHERE id=#{id}")
    int update(BatchRunDO run);

    @Select("SELECT * FROM accounting_batch_run WHERE batch_type=#{batchType} AND biz_date=#{bizDate} "
            + "AND shard_index=#{shardIndex} AND shard_total=#{shardTotal}")
    BatchRunDO select(@Param("batchType") String batchType, @Param("bizDate") long bizDate,
                      @Param("shardIndex") int shardIndex, @Param("shardTotal") int shardTotal);

    @Select("SELECT * FROM accounting_batch_run WHERE id=#{id}")
    BatchRunDO selectById(String id);

    @Select("<script>SELECT * FROM accounting_batch_run "
            + "<where><if test='batchType != null and batchType != \"\"'> AND batch_type=#{batchType}</if>"
            + "<if test='bizDate != null'> AND biz_date=#{bizDate}</if></where>"
            + " ORDER BY started_at DESC, id DESC LIMIT #{limit}</script>")
    List<BatchRunDO> selectRecent(@Param("batchType") String batchType, @Param("bizDate") Long bizDate,
                                  @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM accounting_batch_run")
    int count();

    @Select("SELECT next_value_for('seq_subject')")
    long nextId();
}