package com.qoder.payment.payment.mapper;

import com.qoder.payment.payment.dataobject.PaymentDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PaymentMapper {

    @Select("SELECT next_value_for('seq_payment')")
    long nextId();

    @Insert("INSERT INTO payment(id,order_id,merchant_id,biz_type,method,payer_account_id,card_no,merchant_account_id,amount,fee,status,"
            + "refunded_amount,refunded_fee,channel_biz_no,fail_reason,request_id,xid,callback_url,created_at,pay_mode,channel_code,stage_seq,merge_id) "
            + "VALUES(#{id},#{orderId},#{merchantId},#{bizType},#{method},#{payerAccountId},#{cardNo},#{merchantAccountId},#{amount},#{fee},#{status},"
            + "#{refundedAmount},#{refundedFee},#{channelBizNo},#{failReason},#{requestId},#{xid},#{callbackUrl},#{createdAt},"
            + "#{payMode},#{channelCode},#{stageSeq},#{mergeId})")
    int insert(PaymentDO payment);

    @Select("SELECT * FROM payment WHERE id=#{id}")
    PaymentDO selectById(String id);

    @Select("SELECT * FROM payment WHERE request_id=#{requestId}")
    PaymentDO selectByRequestId(String requestId);

    @Select("SELECT * FROM payment WHERE xid=#{xid}")
    PaymentDO selectByXid(String xid);

    @Select("SELECT * FROM payment ORDER BY created_at DESC, id DESC")
    List<PaymentDO> selectAll();

    /** 补单扫描：超时在途单（PROCESSING 且创建早于 before），按受理时间升序限量。 */
    @Select("SELECT * FROM payment WHERE status='PROCESSING' AND created_at < #{before} ORDER BY created_at ASC LIMIT #{limit}")
    List<PaymentDO> selectProcessingBefore(@Param("before") long before, @Param("limit") int limit);

    /** 补单留痕：只累加计数与时间戳，绝不触碰状态/金额（状态翻转只经 TCC 完结监听）。 */
    @Update("UPDATE payment SET reconcile_count=reconcile_count+1, last_reconcile_at=#{now} WHERE id=#{id}")
    int bumpReconcile(@Param("id") String id, @Param("now") long now);

    @Update("UPDATE payment SET xid=#{xid} WHERE id=#{id}")
    int updateXid(@Param("id") String id, @Param("xid") String xid);

    @Update("UPDATE payment SET status=#{status}, fail_reason=#{failReason} WHERE id=#{id} AND status='PROCESSING'")
    int finish(@Param("id") String id, @Param("status") String status, @Param("failReason") String failReason);

    /**
     * P360 受控终态修复：仅 FAILED→SUCCESS 的 CAS（恰 1 行才生效），清空 fail_reason。
     * 只翻状态、不动任何资金/凭证；权威依据为 dtx=CONFIRMED 且凭证齐全（服务层前置校验）。
     */
    @Update("UPDATE payment SET status='SUCCESS', fail_reason=NULL WHERE id=#{id} AND status='FAILED'")
    int casRepairTerminal(@Param("id") String id);

    @Update("UPDATE payment SET refunded_amount=#{refundedAmount}, refunded_fee=#{refundedFee}, "
            + "status = CASE WHEN #{refundedAmount} >= amount THEN 'REFUNDED' ELSE status END WHERE id=#{id}")
    int updateRefunded(@Param("id") String id, @Param("refundedAmount") long refundedAmount, @Param("refundedFee") long refundedFee);

    // ---------- P-5 关闭/撤销 CAS（additive：只增方法，既有 SQL 一字未改） ----------

    /** 关闭在途单：PROCESSING→CLOSED（CAS，与受理/完结监听竞态只一方生效）。 */
    @Update("UPDATE payment SET status='CLOSED', close_type='CLOSE', closed_at=#{now} WHERE id=#{id} AND status='PROCESSING'")
    int closeProcessing(@Param("id") String id, @Param("now") long now);

    /** 关闭竞态兜底：TCC 回滚监听已把 PROCESSING 翻 FAILED 时，凭同一 xid 把 FAILED 收敛为 CLOSED。 */
    @Update("UPDATE payment SET status='CLOSED', close_type='CLOSE', closed_at=#{now} "
            + "WHERE id=#{id} AND status='FAILED' AND xid=#{xid}")
    int closeFailedAfterRollback(@Param("id") String id, @Param("xid") String xid, @Param("now") long now);

    /** 当日撤销：SUCCESS→CANCELLED（CAS 守卫 refunded_amount=0，防与退款竞态）。 */
    @Update("UPDATE payment SET status='CANCELLED', close_type='CANCEL', closed_at=#{now} "
            + "WHERE id=#{id} AND status='SUCCESS' AND refunded_amount=0")
    int cancelSuccess(@Param("id") String id, @Param("now") long now);

    // ---------- P2-E P-7 营销抵扣编排（additive：只增方法，既有 SQL 一字未改） ----------

    /**
     * 营销净抵扣额 + 编排状态回填（<b>绝不触碰 amount/fee/status</b>——amount 仍是订单全额=fee 基数，
     * 支付单状态只经 TCC 完结监听翻转）。无营销诉求的单恒 marketing_amount=0 / marketing_status='NONE'。
     */
    @Update("UPDATE payment SET marketing_amount=#{amount}, marketing_status=#{status} WHERE id=#{id}")
    int updateMarketing(@Param("id") String id, @Param("amount") long amount, @Param("status") String status);

    @Update("UPDATE payment SET marketing_status=#{status} WHERE id=#{id}")
    int updateMarketingStatus(@Param("id") String id, @Param("status") String status);

    /** 回退兜底扫描：营销权益尚未退回干净的支付单（ROLLBACK_PENDING），按受理时间升序限量。 */
    @Select("SELECT * FROM payment WHERE marketing_status='ROLLBACK_PENDING' ORDER BY created_at ASC LIMIT #{limit}")
    List<PaymentDO> selectMarketingRollbackPending(@Param("limit") int limit);
}