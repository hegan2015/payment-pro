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

    @Select("SELECT next_value_for('seq_notify')")
    long nextId();

    @Insert("INSERT INTO notify_record(id,event,biz_id,payload,callback_url,status,attempts,next_retry_at,last_error,created_at,app_id,sign_type,signature,nonce) "
            + "VALUES(#{id},#{event},#{bizId},#{payload},#{callbackUrl},#{status},#{attempts},#{nextRetryAt},#{lastError},#{createdAt},#{appId},#{signType},#{signature},#{nonce})")
    int insert(NotifyRecordDO record);

    @Select("SELECT * FROM notify_record WHERE id=#{id}")
    NotifyRecordDO selectById(String id);

    @Select("SELECT * FROM notify_record WHERE biz_id=#{bizId} ORDER BY created_at DESC, id DESC")
    List<NotifyRecordDO> selectByBizId(String bizId);

    @Select("SELECT * FROM notify_record ORDER BY created_at DESC, id DESC")
    List<NotifyRecordDO> selectAll();

    @Select("SELECT * FROM notify_record WHERE status='PENDING' AND next_retry_at <= #{now} ORDER BY id LIMIT #{limit}")
    List<NotifyRecordDO> selectPending(@Param("now") long now, @Param("limit") int limit);

    /** N-2 死信兜底扫描：重试耗尽 FAILED 记录（差错单幂等补建）。 */
    @Select("SELECT * FROM notify_record WHERE status='FAILED' ORDER BY id DESC LIMIT #{limit}")
    List<NotifyRecordDO> selectFailed(@Param("limit") int limit);

    /** N-2 死信重放：仅 FAILED 可重置（CAS 幂等，非 FAILED 返回 0 行）。 */
    @Update("UPDATE notify_record SET status='PENDING', attempts=0, next_retry_at=0, last_error=NULL WHERE id=#{id} AND status='FAILED'")
    int resetForReplay(@Param("id") String id);

    @Update("UPDATE notify_record SET status=#{status}, attempts=#{attempts}, next_retry_at=#{nextRetryAt}, last_error=#{lastError} WHERE id=#{id}")
    int updateDelivery(@Param("id") String id, @Param("status") String status, @Param("attempts") long attempts,
                       @Param("nextRetryAt") long nextRetryAt, @Param("lastError") String lastError);

    @Update("UPDATE notify_record SET sign_type=#{signType}, signature=#{signature}, nonce=#{nonce} WHERE id=#{id}")
    int updateSignature(@Param("id") String id, @Param("signType") String signType,
                        @Param("signature") String signature, @Param("nonce") String nonce);

    // ---------------- N-4 通知查询/重发（additive，既有语句一字不改） ----------------

    /** N-4 分页查询：商户/事件/状态/业务单号/时间窗 AND 组合，created_at DESC 稳定排序。 */
    @Select("<script>SELECT * FROM notify_record"
            + "<where>"
            + "<if test='appId != null and appId != \"\"'> AND app_id=#{appId}</if>"
            + "<if test='event != null and event != \"\"'> AND event=#{event}</if>"
            + "<if test='status != null and status != \"\"'> AND status=#{status}</if>"
            + "<if test='bizId != null and bizId != \"\"'> AND biz_id=#{bizId}</if>"
            + "<if test='from != null'> AND created_at &gt;= #{from}</if>"
            + "<if test='to != null'> AND created_at &lt;= #{to}</if>"
            + "</where>"
            + " ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<NotifyRecordDO> selectPage(@Param("appId") String appId, @Param("event") String event,
                                    @Param("status") String status, @Param("bizId") String bizId,
                                    @Param("from") Long from, @Param("to") Long to,
                                    @Param("size") int size, @Param("offset") int offset);

    /** N-4 分页总数（与 selectPage 同条件）。 */
    @Select("<script>SELECT COUNT(*) FROM notify_record"
            + "<where>"
            + "<if test='appId != null and appId != \"\"'> AND app_id=#{appId}</if>"
            + "<if test='event != null and event != \"\"'> AND event=#{event}</if>"
            + "<if test='status != null and status != \"\"'> AND status=#{status}</if>"
            + "<if test='bizId != null and bizId != \"\"'> AND biz_id=#{bizId}</if>"
            + "<if test='from != null'> AND created_at &gt;= #{from}</if>"
            + "<if test='to != null'> AND created_at &lt;= #{to}</if>"
            + "</where></script>")
    long countPage(@Param("appId") String appId, @Param("event") String event,
                   @Param("status") String status, @Param("bizId") String bizId,
                   @Param("from") Long from, @Param("to") Long to);

    /**
     * N-4 重发 CAS：仅 FAILED/PULL 可重置（DELIVERED/PENDING 返回 0 行 → 不重复推送）。
     * 多实例并发下只有一个 UPDATE 命中，resend_count 原子 +1；不新增记录。
     */
    @Update("UPDATE notify_record SET status='PENDING', attempts=0, next_retry_at=0, last_error=NULL, "
            + "resend_count=COALESCE(resend_count,0)+1 WHERE id=#{id} AND status IN ('FAILED','PULL')")
    int resetForResend(@Param("id") String id);
}