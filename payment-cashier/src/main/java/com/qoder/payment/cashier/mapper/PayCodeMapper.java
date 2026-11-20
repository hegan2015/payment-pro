package com.qoder.payment.cashier.mapper;

import com.qoder.payment.cashier.model.PayCodeDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** C-2 付款码 Mapper：生成幂等 + 一次性核销 CAS（防重放）+ 过期标记。 */
@Mapper
public interface PayCodeMapper {

    @Insert("INSERT INTO pay_code(code,type,account_id,order_id,amount,status,expire_at,request_id,"
            + "qr_content,merchant_name,subject,created_at) "
            + "VALUES(#{code},#{type},#{accountId},#{orderId},#{amount},#{status},#{expireAt},#{requestId},"
            + "#{qrContent},#{merchantName},#{subject},#{createdAt})")
    int insert(PayCodeDO code);

    @Select("SELECT * FROM pay_code WHERE code=#{code}")
    PayCodeDO selectByCode(@Param("code") String code);

    @Select("SELECT * FROM pay_code WHERE request_id=#{requestId} AND type=#{type} ORDER BY created_at DESC LIMIT 1")
    PayCodeDO selectByRequest(@Param("requestId") String requestId, @Param("type") String type);

    /** 一次性核销：ACTIVE→CONSUMED 只一次（CAS），写入核销 requestId 供幂等/防重放判定。 */
    @Update("UPDATE pay_code SET status='CONSUMED', consume_request_id=#{consumeRequestId}, consumed_at=#{consumedAt} "
            + "WHERE code=#{code} AND status='ACTIVE'")
    int casConsume(@Param("code") String code, @Param("consumeRequestId") String consumeRequestId,
                   @Param("consumedAt") long consumedAt);

    /** 过期标记：仅 ACTIVE 且已过期才翻 EXPIRED（不动 CONSUMED）。 */
    @Update("UPDATE pay_code SET status='EXPIRED' WHERE code=#{code} AND status='ACTIVE' AND expire_at<=#{now}")
    int markExpired(@Param("code") String code, @Param("now") long now);
}