package com.qoder.payment.channel.mapper;

import com.qoder.payment.channel.dataobject.SignDeductDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * CH-6 代扣单表（幂等键 request_id UNIQUE；周期累计额度按 period_key 聚合）。
 *
 * <p>额度占用口径：SUCCESS + 在途（INIT/PROCESSING）一并计入，杜绝并发/重试窗口超扣；
 * FAILED/TERMINATED 释放额度（未动账或已回滚）。
 */
@Mapper
public interface SignDeductMapper {

    @Insert("INSERT INTO sign_deduct(deduct_no,sign_no,account_id,merchant_id,request_id,attempt_request_id,"
            + "order_id,payment_id,amount,status,attempts,max_retry,next_retry_at,fail_reason,period_key,"
            + "channel_code,created_at,updated_at,finished_at) "
            + "VALUES(#{deductNo},#{signNo},#{accountId},#{merchantId},#{requestId},#{attemptRequestId},"
            + "#{orderId},#{paymentId},#{amount},#{status},#{attempts},#{maxRetry},#{nextRetryAt},#{failReason},"
            + "#{periodKey},#{channelCode},#{createdAt},#{updatedAt},#{finishedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SignDeductDO deduct);

    @Select("SELECT * FROM sign_deduct WHERE deduct_no=#{deductNo}")
    SignDeductDO selectByDeductNo(String deductNo);

    @Select("SELECT * FROM sign_deduct WHERE request_id=#{requestId}")
    SignDeductDO selectByRequestId(String requestId);

    @Select("<script>SELECT * FROM sign_deduct WHERE 1=1 "
            + "<if test='signNo != null'> AND sign_no=#{signNo}</if>"
            + "<if test='status != null'> AND status=#{status}</if>"
            + " ORDER BY id DESC LIMIT #{limit}</script>")
    List<SignDeductDO> selectList(@Param("signNo") String signNo, @Param("status") String status,
                                  @Param("limit") int limit);

    /** 周期已占用额度（分）：SUCCESS + 在途。 */
    @Select("SELECT COALESCE(SUM(amount),0) FROM sign_deduct WHERE sign_no=#{signNo} AND period_key=#{periodKey} "
            + "AND status IN ('INIT','PROCESSING','SUCCESS')")
    long sumPeriod(@Param("signNo") String signNo, @Param("periodKey") String periodKey);

    @Select("SELECT COUNT(*) FROM sign_deduct WHERE sign_no=#{signNo} AND period_key=#{periodKey} "
            + "AND status IN ('INIT','PROCESSING','SUCCESS')")
    int countPeriod(@Param("signNo") String signNo, @Param("periodKey") String periodKey);

    /** 绑定交易单 + 本次尝试幂等键（INIT/FAILED → PROCESSING，CAS 防并发重复受理）。 */
    @Update("UPDATE sign_deduct SET order_id=#{orderId}, attempt_request_id=#{attemptRequestId}, "
            + "attempts=#{attempts}, status='PROCESSING', payment_id=#{paymentId}, updated_at=#{updatedAt} "
            + "WHERE deduct_no=#{deductNo} AND status=#{from}")
    int bindAttempt(@Param("deductNo") String deductNo, @Param("from") String from,
                    @Param("orderId") String orderId, @Param("attemptRequestId") String attemptRequestId,
                    @Param("attempts") int attempts, @Param("paymentId") String paymentId,
                    @Param("updatedAt") long updatedAt);

    /** 终态/失败落地（CAS 只从 PROCESSING 翻，杜绝覆盖已成功单）。 */
    @Update("UPDATE sign_deduct SET status=#{status}, payment_id=#{paymentId}, fail_reason=#{failReason}, "
            + "next_retry_at=#{nextRetryAt}, finished_at=#{finishedAt}, updated_at=#{updatedAt} "
            + "WHERE deduct_no=#{deductNo} AND status='PROCESSING'")
    int finish(@Param("deductNo") String deductNo, @Param("status") String status,
               @Param("paymentId") String paymentId, @Param("failReason") String failReason,
               @Param("nextRetryAt") Long nextRetryAt, @Param("finishedAt") Long finishedAt,
               @Param("updatedAt") long updatedAt);

    /** 受理前失败（尚未建支付单）：INIT → FAILED/TERMINATED，留痕不悬挂。 */
    @Update("UPDATE sign_deduct SET status=#{status}, fail_reason=#{failReason}, attempts=#{attempts}, "
            + "next_retry_at=#{nextRetryAt}, finished_at=#{finishedAt}, updated_at=#{updatedAt} "
            + "WHERE deduct_no=#{deductNo} AND status='INIT'")
    int failInit(@Param("deductNo") String deductNo, @Param("status") String status,
                 @Param("failReason") String failReason, @Param("attempts") int attempts,
                 @Param("nextRetryAt") Long nextRetryAt, @Param("finishedAt") Long finishedAt,
                 @Param("updatedAt") long updatedAt);

    /** 在途单回填支付单号/说明（异步受理或受理状态未知时留痕，交补单收敛，绝不臆断失败）。 */
    @Update("UPDATE sign_deduct SET payment_id=#{paymentId}, fail_reason=#{failReason}, updated_at=#{updatedAt} "
            + "WHERE deduct_no=#{deductNo} AND status='PROCESSING'")
    int noteProcessing(@Param("deductNo") String deductNo, @Param("paymentId") String paymentId,
                       @Param("failReason") String failReason, @Param("updatedAt") long updatedAt);

    /** 人工终止：INIT/FAILED/PROCESSING → TERMINATED（SUCCESS 不可终止，资金已成功不得抹除）。 */
    @Update("UPDATE sign_deduct SET status='TERMINATED', fail_reason=#{reason}, finished_at=#{now}, "
            + "updated_at=#{now} WHERE deduct_no=#{deductNo} AND status IN ('INIT','FAILED','PROCESSING')")
    int casTerminate(@Param("deductNo") String deductNo, @Param("reason") String reason, @Param("now") long now);

    /**
     * 到期可重试的失败单。语义：max_retry=最大<b>重试</b>次数 ⇒ 总尝试上限 = max_retry + 1，
     * 故 attempts <= max_retry 时仍允许下一次尝试（与 finishFailed 的 exhausted 判定同口径）。
     */
    @Select("SELECT * FROM sign_deduct WHERE status='FAILED' AND attempts <= max_retry "
            + "AND next_retry_at <= #{now} ORDER BY next_retry_at ASC LIMIT #{limit}")
    List<SignDeductDO> selectRetryDue(@Param("now") long now, @Param("limit") int limit);

    /** 在途单（异步渠道/宕机窗口）：交补单收敛，绝不盲目重试。 */
    @Select("SELECT * FROM sign_deduct WHERE status='PROCESSING' ORDER BY id ASC LIMIT #{limit}")
    List<SignDeductDO> selectProcessing(@Param("limit") int limit);

    /** 重试耗尽终止（FAILED 且 attempts > max_retry）。 */
    @Select("SELECT * FROM sign_deduct WHERE status='FAILED' AND attempts > max_retry "
            + "ORDER BY id ASC LIMIT #{limit}")
    List<SignDeductDO> selectExhausted(@Param("limit") int limit);
}