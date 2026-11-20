package com.qoder.payment.channel.mapper;

import com.qoder.payment.channel.dataobject.SignContractDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * CH-6 签约协议表（幂等键 request_id UNIQUE + 三元组有效协议唯一由服务层 CAS 保证）。
 * 只增不改既有行：状态翻转一律 CAS（SIGNED → CANCELLED/EXPIRED），绝不 DELETE。
 */
@Mapper
public interface SignContractMapper {

    @Insert("INSERT INTO sign_contract(sign_no,account_id,merchant_id,channel_code,channel_sign_no,"
            + "card_no_hash,card_no_mask,card_no_cipher,status,single_limit,period_limit,period_type,"
            + "effective_at,expired_at,deduct_day,max_retry,retry_interval_ms,callback_url,app_id,"
            + "request_id,authorized_scope,active_key,signed_at,cancelled_at,cancel_reason,created_at,updated_at) "
            + "VALUES(#{signNo},#{accountId},#{merchantId},#{channelCode},#{channelSignNo},"
            + "#{cardNoHash},#{cardNoMask},#{cardNoCipher},#{status},#{singleLimit},#{periodLimit},#{periodType},"
            + "#{effectiveAt},#{expiredAt},#{deductDay},#{maxRetry},#{retryIntervalMs},#{callbackUrl},#{appId},"
            + "#{requestId},#{authorizedScope},#{activeKey},#{signedAt},#{cancelledAt},#{cancelReason},"
            + "#{createdAt},#{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SignContractDO contract);

    @Select("SELECT * FROM sign_contract WHERE sign_no=#{signNo}")
    SignContractDO selectBySignNo(String signNo);

    /** 代扣额度复核加行锁（H2/MySQL 同构）：串行化同协议并发代扣，杜绝周期累计超扣。 */
    @Select("SELECT * FROM sign_contract WHERE sign_no=#{signNo} FOR UPDATE")
    SignContractDO selectBySignNoForUpdate(String signNo);

    @Select("SELECT * FROM sign_contract WHERE request_id=#{requestId}")
    SignContractDO selectByRequestId(String requestId);

    /** 同 (accountId,merchantId,channelCode) 的有效协议（幂等键 SIGN 三元组）。 */
    @Select("SELECT * FROM sign_contract WHERE account_id=#{accountId} AND merchant_id=#{merchantId} "
            + "AND channel_code=#{channelCode} AND status='SIGNED' ORDER BY id DESC LIMIT 1")
    SignContractDO selectActive(@Param("accountId") String accountId, @Param("merchantId") String merchantId,
                                @Param("channelCode") String channelCode);

    @Select("<script>SELECT * FROM sign_contract WHERE 1=1 "
            + "<if test='accountId != null'> AND account_id=#{accountId}</if>"
            + "<if test='merchantId != null'> AND merchant_id=#{merchantId}</if>"
            + "<if test='status != null'> AND status=#{status}</if>"
            + " ORDER BY id DESC LIMIT #{limit}</script>")
    List<SignContractDO> selectList(@Param("accountId") String accountId, @Param("merchantId") String merchantId,
                                    @Param("status") String status, @Param("limit") int limit);

    @Select("SELECT * FROM sign_contract WHERE account_id=#{accountId} ORDER BY id DESC LIMIT #{limit}")
    List<SignContractDO> selectByAccount(@Param("accountId") String accountId, @Param("limit") int limit);

    /** 解约 CAS：只有 SIGNED 能翻 CANCELLED（重复解约影响 0 行 → 幂等 noop），同时释放有效协议唯一键。 */
    @Update("UPDATE sign_contract SET status='CANCELLED', cancelled_at=#{cancelledAt}, "
            + "cancel_reason=#{cancelReason}, active_key=NULL, updated_at=#{updatedAt} "
            + "WHERE sign_no=#{signNo} AND status='SIGNED'")
    int casCancel(@Param("signNo") String signNo, @Param("cancelledAt") long cancelledAt,
                  @Param("cancelReason") String cancelReason, @Param("updatedAt") long updatedAt);

    /** 到期 CAS：SIGNED 且 expired_at 已过 → EXPIRED（作业置位，代扣侧另有受理前有效期校验）。 */
    @Update("UPDATE sign_contract SET status='EXPIRED', active_key=NULL, updated_at=#{updatedAt} "
            + "WHERE sign_no=#{signNo} AND status='SIGNED' AND expired_at < #{now}")
    int casExpire(@Param("signNo") String signNo, @Param("now") long now, @Param("updatedAt") long updatedAt);

    @Select("SELECT * FROM sign_contract WHERE status='SIGNED' AND expired_at < #{now} "
            + "ORDER BY expired_at ASC LIMIT #{limit}")
    List<SignContractDO> selectExpired(@Param("now") long now, @Param("limit") int limit);
}