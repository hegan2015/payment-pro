package com.qoder.payment.payment.mapper;

import com.qoder.payment.payment.dataobject.RefundDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RefundMapper {

    @Select("SELECT next_value_for('seq_refund')")
    long nextId();

    @Insert("INSERT INTO refund_record(id,payment_id,amount,fee_refund,request_id,xid,status,biz_no,created_at,channel_order_no,leg_alloc_json) "
            + "VALUES(#{id},#{paymentId},#{amount},#{feeRefund},#{requestId},#{xid},#{status},#{bizNo},#{createdAt},#{channelOrderNo},#{legAllocJson})")
    int insert(RefundDO refund);

    @Select("SELECT * FROM refund_record WHERE id=#{id}")
    RefundDO selectById(String id);

    @Select("SELECT * FROM refund_record WHERE request_id=#{requestId}")
    RefundDO selectByRequestId(String requestId);

    @Select("SELECT * FROM refund_record WHERE xid=#{xid}")
    RefundDO selectByXid(String xid);

    @Update("UPDATE refund_record SET xid=#{xid} WHERE id=#{id}")
    int updateXid(@Param("id") String id, @Param("xid") String xid);

    @Update("UPDATE refund_record SET status=#{status} WHERE id=#{id} AND status='PROCESSING'")
    int finish(@Param("id") String id, @Param("status") String status);

    // ---------- P-4 退款异步化 additive（既有 SQL 一字未改） ----------

    @Update("UPDATE refund_record SET status=#{status}, fail_reason=#{failReason} WHERE id=#{id} AND status='PROCESSING'")
    int finishWithReason(@Param("id") String id, @Param("status") String status, @Param("failReason") String failReason);

    /** 退款补单扫描：超时在途退款单（PROCESSING 且创建早于 before）。 */
    @Select("SELECT * FROM refund_record WHERE status='PROCESSING' AND created_at < #{before} ORDER BY created_at ASC LIMIT #{limit}")
    java.util.List<RefundDO> selectProcessingBefore(@Param("before") long before, @Param("limit") int limit);

    /** 补单留痕：只累加计数与时间戳，状态翻转仍只经 TCC 完结监听。 */
    @Update("UPDATE refund_record SET reconcile_count=reconcile_count+1, last_reconcile_at=#{now} WHERE id=#{id}")
    int bumpReconcile(@Param("id") String id, @Param("now") long now);

    /** P-5 撤销竞态守卫：存在在途退款则不可撤销。 */
    @Select("SELECT COUNT(*) FROM refund_record WHERE payment_id=#{paymentId} AND status='PROCESSING'")
    int countProcessingByPayment(String paymentId);
}