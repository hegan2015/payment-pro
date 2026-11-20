package com.qoder.payment.payment.mapper;

import com.qoder.payment.payment.dataobject.OutboxDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface OutboxMapper {

    @Insert("INSERT INTO outbox(event,biz_id,payload,status,attempts,next_retry_at,created_at) "
            + "VALUES(#{event},#{bizId},#{payload},'PENDING',0,#{nextRetryAt},#{createdAt})")
    int insert(@Param("event") String event, @Param("bizId") String bizId,
               @Param("payload") String payload, @Param("nextRetryAt") long nextRetryAt,
               @Param("createdAt") long createdAt);

    /**
     * I-4 带路由 topic 的插入（additive：仅多写 topic 元数据列，状态机/幂等键与 insert 完全一致）。
     * 原 insert 保留不动，兼容既有调用。
     */
    @Insert("INSERT INTO outbox(event,biz_id,payload,status,attempts,next_retry_at,created_at,topic) "
            + "VALUES(#{event},#{bizId},#{payload},'PENDING',0,#{nextRetryAt},#{createdAt},#{topic})")
    int insertRouted(@Param("event") String event, @Param("bizId") String bizId,
                     @Param("payload") String payload, @Param("nextRetryAt") long nextRetryAt,
                     @Param("createdAt") long createdAt, @Param("topic") String topic);

    /** I-4 半消息回执/回查判定留痕：只写 msg_id/half_state，绝不动 status/attempts/next_retry_at。 */
    @Update("UPDATE outbox SET msg_id=#{msgId}, half_state=#{halfState} WHERE id=#{id}")
    int updateMqMeta(@Param("id") Long id, @Param("msgId") String msgId, @Param("halfState") String halfState);

    @Select("SELECT * FROM outbox WHERE status='PENDING' AND next_retry_at <= #{now} ORDER BY id LIMIT #{limit}")
    List<OutboxDO> selectPending(@Param("now") long now, @Param("limit") int limit);

    @Select("SELECT * FROM outbox WHERE event=#{event} AND biz_id=#{bizId} ORDER BY id DESC LIMIT 1")
    OutboxDO selectByEventBizId(@Param("event") String event, @Param("bizId") String bizId);

    @Update("UPDATE outbox SET status='DELIVERED' WHERE id=#{id}")
    int markDelivered(Long id);

    @Update("UPDATE outbox SET attempts=#{attempts}, next_retry_at=#{nextRetryAt}, last_error=#{lastError} WHERE id=#{id}")
    int markRetry(@Param("id") Long id, @Param("attempts") int attempts,
                  @Param("nextRetryAt") long nextRetryAt, @Param("lastError") String lastError);

    @Update("UPDATE outbox SET status='DEAD', last_error=#{lastError} WHERE id=#{id}")
    int markDead(@Param("id") Long id, @Param("lastError") String lastError);
}