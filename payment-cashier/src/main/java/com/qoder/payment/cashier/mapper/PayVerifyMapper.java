package com.qoder.payment.cashier.mapper;

import com.qoder.payment.cashier.model.PayCredentialDO;
import com.qoder.payment.cashier.model.VerifySessionDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

/** C-3 支付验证 Mapper：口令（哈希/失败计数/锁定）+ 验证会话（一次性 CAS）+ 短信沙箱码。 */
@Mapper
public interface PayVerifyMapper {

    // ---------- 支付口令 ----------
    @Select("SELECT * FROM pay_credential WHERE member_id=#{memberId}")
    PayCredentialDO selectCredential(@Param("memberId") String memberId);

    @Insert("INSERT INTO pay_credential(member_id,password_hash,salt,fail_count,locked_until,created_at,updated_at) "
            + "VALUES(#{memberId},#{passwordHash},#{salt},#{failCount},#{lockedUntil},#{createdAt},#{updatedAt})")
    int insertCredential(PayCredentialDO cred);

    @Update("UPDATE pay_credential SET password_hash=#{hash}, salt=#{salt}, fail_count=0, locked_until=NULL, "
            + "updated_at=#{updatedAt} WHERE member_id=#{memberId}")
    int updatePassword(@Param("memberId") String memberId, @Param("hash") String hash,
                       @Param("salt") String salt, @Param("updatedAt") long updatedAt);

    @Update("UPDATE pay_credential SET fail_count=#{failCount}, locked_until=#{lockedUntil}, updated_at=#{updatedAt} "
            + "WHERE member_id=#{memberId}")
    int updateFail(@Param("memberId") String memberId, @Param("failCount") int failCount,
                   @Param("lockedUntil") Long lockedUntil, @Param("updatedAt") long updatedAt);

    @Update("UPDATE pay_credential SET fail_count=0, locked_until=NULL, updated_at=#{updatedAt} WHERE member_id=#{memberId}")
    int resetFail(@Param("memberId") String memberId, @Param("updatedAt") long updatedAt);

    // ---------- 验证会话 ----------
    @Insert("INSERT INTO verify_session(id,member_id,account_id,order_id,amount,verify_type,status,expire_at,"
            + "request_id,created_at) VALUES(#{id},#{memberId},#{accountId},#{orderId},#{amount},#{verifyType},"
            + "#{status},#{expireAt},#{requestId},#{createdAt})")
    int insertSession(VerifySessionDO session);

    @Select("SELECT * FROM verify_session WHERE id=#{id}")
    VerifySessionDO selectSession(@Param("id") String id);

    @Select("SELECT * FROM verify_session WHERE request_id=#{requestId} ORDER BY created_at DESC LIMIT 1")
    VerifySessionDO selectSessionByRequest(@Param("requestId") String requestId);

    /** 一次性核销会话：ACTIVE→USED 只一次（CAS）。 */
    @Update("UPDATE verify_session SET status='USED', used_at=#{usedAt} WHERE id=#{id} AND status='ACTIVE'")
    int casUseSession(@Param("id") String id, @Param("usedAt") long usedAt);

    // ---------- 短信验证码（沙箱） ----------
    @Insert("INSERT INTO sms_code(id,member_id,account_id,order_id,code,status,expire_at,created_at) "
            + "VALUES(#{id},#{memberId},#{accountId},#{orderId},#{code},'ACTIVE',#{expireAt},#{createdAt})")
    int insertSms(@Param("id") String id, @Param("memberId") String memberId, @Param("accountId") String accountId,
                  @Param("orderId") String orderId, @Param("code") String code,
                  @Param("expireAt") long expireAt, @Param("createdAt") long createdAt);

    @Select("SELECT * FROM sms_code WHERE member_id=#{memberId} AND status='ACTIVE' AND expire_at>#{now} "
            + "ORDER BY created_at DESC LIMIT 1")
    Map<String, Object> selectActiveSms(@Param("memberId") String memberId, @Param("now") long now);

    @Update("UPDATE sms_code SET status='USED' WHERE id=#{id} AND status='ACTIVE'")
    int markSmsUsed(@Param("id") String id);
}