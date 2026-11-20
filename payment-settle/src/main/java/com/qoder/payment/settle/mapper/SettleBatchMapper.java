package com.qoder.payment.settle.mapper;

import com.qoder.payment.facade.settle.SettleBatchDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * ST-1 结算批次 + 批次明细（单库不分片：状态机含 UPDATE，与幂等/协调器表同归属）。
 *
 * <p>明细只 INSERT，唯一键 (batch_id,record_id) 去重 ⇒ 重复跑批/分片广播不重不漏。
 */
@Mapper
public interface SettleBatchMapper {

    @Select("SELECT * FROM settle_batch WHERE id=#{id}")
    SettleBatchDTO selectById(String id);

    @Select("SELECT * FROM settle_batch WHERE merchant_id=#{merchantId} AND biz_date=#{bizDate}")
    SettleBatchDTO selectByKey(@Param("merchantId") String merchantId, @Param("bizDate") long bizDate);

    @Select("SELECT * FROM settle_batch WHERE merchant_id=#{merchantId} ORDER BY biz_date DESC, id DESC")
    List<SettleBatchDTO> selectByMerchant(String merchantId);

    @Select("SELECT * FROM settle_batch ORDER BY biz_date DESC, merchant_id, id")
    List<SettleBatchDTO> selectAll();

    @Select("SELECT * FROM settle_batch WHERE merchant_id=#{merchantId} AND biz_date=#{bizDate} AND status='SETTLED'")
    SettleBatchDTO selectSettled(@Param("merchantId") String merchantId, @Param("bizDate") long bizDate);

    @Insert("INSERT INTO settle_batch(id,merchant_id,biz_date,cycle_days,gross,fee,net,refund,record_count,"
            + "status,fail_reason,shard_index,shard_total,created_at,settled_at) "
            + "VALUES(#{id},#{merchantId},#{bizDate},#{cycleDays},#{gross},#{fee},#{net},#{refund},#{recordCount},"
            + "#{status},#{failReason},#{shardIndex},#{shardTotal},#{createdAt},#{settledAt})")
    int insert(SettleBatchDTO batch);

    @Update("UPDATE settle_batch SET status=#{status}, fail_reason=#{failReason} WHERE id=#{id}")
    int updateStatus(@Param("id") String id, @Param("status") String status,
                     @Param("failReason") String failReason);

    @Update("UPDATE settle_batch SET status=#{status}, gross=#{gross}, fee=#{fee}, net=#{net}, refund=#{refund}, "
            + "record_count=#{recordCount}, fail_reason=#{failReason}, settled_at=#{settledAt} WHERE id=#{id}")
    int finish(SettleBatchDTO batch);

    @Insert("INSERT INTO settle_batch_item(id,batch_id,record_id,merchant_id,type,gross,fee,net,created_at) "
            + "VALUES(#{id},#{batchId},#{recordId},#{merchantId},#{type},#{gross},#{fee},#{net},#{createdAt})")
    int insertItem(@Param("id") String id, @Param("batchId") String batchId, @Param("recordId") String recordId,
                   @Param("merchantId") String merchantId, @Param("type") String type,
                   @Param("gross") long gross, @Param("fee") long fee, @Param("net") long net,
                   @Param("createdAt") long createdAt);

    @Select("SELECT COUNT(*) FROM settle_batch_item WHERE batch_id=#{batchId}")
    int countItems(String batchId);

    @Select("SELECT record_id FROM settle_batch_item WHERE merchant_id=#{merchantId}")
    List<String> selectBatchedRecordIds(String merchantId);

    @Select("SELECT SUM(net) FROM settle_batch_item WHERE batch_id=#{batchId}")
    Long sumItemNet(String batchId);
}