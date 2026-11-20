package com.qoder.payment.payment.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * I-5b 跨片全局幂等索引（E-7 核心，<b>单库不分片</b>）。
 *
 * <p>payment 按 id 分片后，{@code uk_payment_request} 只在片内有效：同一 requestId 的两次并发受理
 * 会拿到两个不同 paymentId → 落不同片 → 两个唯一键互不冲突 → 产生两笔支付单（资金重复扣款）。
 * 本索引表以 {@code request_id} 为主键、<b>不分片</b>，先 INSERT 占位再写支付单，
 * 且两者在<b>同一本地事务</b>（单库分表 ⇒ 同一 MySQL 连接）内提交：
 * 并发败者在索引唯一键上阻塞，待胜者提交后拿到既有 paymentId 直接幂等回放。
 *
 * <p><b>ID 形态不变</b>：paymentId 仍是 {@code P+十进制数字}（分片态数字来自雪花而非序列），
 * 不嵌基因、不改前缀、不改长度上限（≤20 &lt; VARCHAR(32)），ride-pro 既有存量 id 完全兼容。
 *
 * <p>refund_record 按 payment_id 分片，同理需要 {@code refund_request_index} 兜底全局退款幂等。
 */
@Mapper
public interface RequestIndexMapper {

    @Insert("INSERT INTO payment_request_index(request_id,payment_id,shard,created_at) "
            + "VALUES(#{requestId},#{paymentId},#{shard},#{createdAt})")
    int insertPayment(@Param("requestId") String requestId, @Param("paymentId") String paymentId,
                      @Param("shard") int shard, @Param("createdAt") long createdAt);

    @Select("SELECT payment_id FROM payment_request_index WHERE request_id=#{requestId}")
    String findPaymentId(String requestId);

    @Insert("INSERT INTO refund_request_index(request_id,refund_id,payment_id,shard,created_at) "
            + "VALUES(#{requestId},#{refundId},#{paymentId},#{shard},#{createdAt})")
    int insertRefund(@Param("requestId") String requestId, @Param("refundId") String refundId,
                     @Param("paymentId") String paymentId, @Param("shard") int shard,
                     @Param("createdAt") long createdAt);

    @Select("SELECT refund_id FROM refund_request_index WHERE request_id=#{requestId}")
    String findRefundId(String requestId);

    @Select("SELECT COUNT(*) FROM payment_request_index")
    long countPayment();

    @Select("SELECT COUNT(*) FROM refund_request_index")
    long countRefund();
}