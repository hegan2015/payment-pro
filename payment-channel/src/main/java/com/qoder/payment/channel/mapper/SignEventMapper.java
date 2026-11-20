package com.qoder.payment.channel.mapper;

import com.qoder.payment.channel.dataobject.SignEventDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** CH-6 事件 outbox（UNIQUE(event,biz_id)：同协议同事件只留一条，notify 域 event:bizId 幂等）。 */
@Mapper
public interface SignEventMapper {

    @Insert("INSERT INTO sign_event(event,biz_id,sign_no,deduct_no,payload,status,attempts,notify_id,"
            + "last_error,created_at,sent_at,next_retry_at) "
            + "VALUES(#{event},#{bizId},#{signNo},#{deductNo},#{payload},#{status},#{attempts},#{notifyId},"
            + "#{lastError},#{createdAt},#{sentAt},#{nextRetryAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SignEventDO event);

    @Select("SELECT * FROM sign_event WHERE sign_no=#{signNo} ORDER BY id ASC")
    List<SignEventDO> selectBySignNo(String signNo);

    @Select("SELECT * FROM sign_event WHERE event=#{event} AND biz_id=#{bizId}")
    SignEventDO selectByEventBiz(@Param("event") String event, @Param("bizId") String bizId);

    @Select("SELECT * FROM sign_event WHERE status IN ('PENDING','FAILED') AND next_retry_at <= #{now} "
            + "ORDER BY id ASC LIMIT #{limit}")
    List<SignEventDO> selectDispatchDue(@Param("now") long now, @Param("limit") int limit);

    @Update("UPDATE sign_event SET status='SENT', notify_id=#{notifyId}, sent_at=#{sentAt}, "
            + "attempts=#{attempts}, last_error=NULL WHERE id=#{id} AND status <> 'SENT'")
    int markSent(@Param("id") long id, @Param("notifyId") String notifyId, @Param("sentAt") long sentAt,
                 @Param("attempts") int attempts);

    @Update("UPDATE sign_event SET status=#{status}, attempts=#{attempts}, last_error=#{lastError}, "
            + "next_retry_at=#{nextRetryAt} WHERE id=#{id} AND status <> 'SENT'")
    int markFailed(@Param("id") long id, @Param("status") String status, @Param("attempts") int attempts,
                   @Param("lastError") String lastError, @Param("nextRetryAt") long nextRetryAt);
}