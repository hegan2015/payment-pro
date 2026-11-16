package com.qoder.payment.notify.mapper;

import com.qoder.payment.notify.dataobject.NotifyRecordDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface NotifyRecordMapper {

    @Select("SELECT NEXT VALUE FOR seq_notify")
    long nextId();

    @Insert("INSERT INTO notify_record(id,event,biz_id,payload,callback_url,status,attempts,next_retry_at,last_error,created_at) "
            + "VALUES(#{id},#{event},#{bizId},#{payload},#{callbackUrl},#{status},#{attempts},#{nextRetryAt},#{lastError},#{createdAt})")
    int insert(NotifyRecordDO record);

    @Select("SELECT * FROM notify_record WHERE id=#{id}")
    NotifyRecordDO selectById(String id);

    @Select("SELECT * FROM notify_record WHERE biz_id=#{bizId} ORDER BY created_at DESC, id DESC")
    List<NotifyRecordDO> selectByBizId(String bizId);

    @Select("SELECT * FROM notify_record ORDER BY created_at DESC, id DESC")
    List<NotifyRecordDO> selectAll();

    @Select("SELECT * FROM notify_record WHERE status='PENDING' AND next_retry_at <= #{now} ORDER BY id LIMIT #{limit}")
    List<NotifyRecordDO> selectPending(@Param("now") long now, @Param("limit") int limit);

    @Update("UPDATE notify_record SET status=#{status}, attempts=#{attempts}, next_retry_at=#{nextRetryAt}, last_error=#{lastError} WHERE id=#{id}")
    int updateDelivery(@Param("id") String id, @Param("status") String status, @Param("attempts") long attempts,
                       @Param("nextRetryAt") long nextRetryAt, @Param("lastError") String lastError);
}