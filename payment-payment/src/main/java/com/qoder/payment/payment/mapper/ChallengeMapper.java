package com.qoder.payment.payment.mapper;

import com.qoder.payment.payment.dataobject.ChallengeDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ChallengeMapper {

    @Insert("INSERT INTO payment_challenge(request_id,order_id,payer_key,amount,method,status,created_at,expire_at) "
            + "VALUES(#{requestId},#{orderId},#{payerKey},#{amount},#{method},#{status},#{createdAt},#{expireAt})")
    int insert(ChallengeDO challenge);

    /** 最近一条待验证挑战（同订单+付款人）。 */
    @Select("SELECT * FROM payment_challenge WHERE order_id=#{orderId} AND payer_key=#{payerKey} "
            + "AND status='CHALLENGED' AND expire_at > #{now} ORDER BY created_at DESC LIMIT 1")
    ChallengeDO selectLatestChallenged(@Param("orderId") String orderId, @Param("payerKey") String payerKey,
                                       @Param("now") long now);

    @Select("SELECT * FROM payment_challenge WHERE continue_request_id=#{requestId} AND status='CONTINUING'")
    ChallengeDO selectGrant(String requestId);

    /** 续付授权：CHALLENGED→CONTINUING（CAS，单_use；绑定续付 requestId + 短窗口）。 */
    @Update("UPDATE payment_challenge SET status='CONTINUING', continue_request_id=#{continueRequestId}, expire_at=#{expireAt} "
            + "WHERE request_id=#{requestId} AND status='CHALLENGED'")
    int grant(@Param("requestId") String requestId, @Param("continueRequestId") String continueRequestId,
              @Param("expireAt") long expireAt);

    /** 授权核销：CONTINUING→CONSUMED（CAS，只一次）。 */
    @Update("UPDATE payment_challenge SET status='CONSUMED', consumed_at=#{now} "
            + "WHERE continue_request_id=#{continueRequestId} AND status='CONTINUING'")
    int consume(@Param("continueRequestId") String continueRequestId, @Param("now") long now);

    /** 验证失败回退授权（允许重新验证续付）。 */
    @Update("UPDATE payment_challenge SET status='CHALLENGED', continue_request_id=NULL, expire_at=#{expireAt} "
            + "WHERE request_id=#{requestId} AND status='CONTINUING'")
    int revoke(@Param("requestId") String requestId, @Param("expireAt") long expireAt);
}