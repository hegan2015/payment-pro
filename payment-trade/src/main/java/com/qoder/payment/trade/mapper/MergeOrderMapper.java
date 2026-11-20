package com.qoder.payment.trade.mapper;

import com.qoder.payment.trade.dataobject.MergeOrderDO;
import com.qoder.payment.trade.dataobject.MergeRelDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** T-5 合并单 + 关联行（单库不分片；状态推进一律 CAS）。 */
@Mapper
public interface MergeOrderMapper {

    @Select("SELECT next_value_for('seq_merge')")
    long nextId();

    @Insert("INSERT INTO merge_order(id,merchant_id,total_amount,status,request_id,carrier_order_id,payment_id,order_count,paid_count,created_at,paid_at) "
            + "VALUES(#{id},#{merchantId},#{totalAmount},#{status},#{requestId},#{carrierOrderId},#{paymentId},#{orderCount},#{paidCount},#{createdAt},#{paidAt})")
    int insert(MergeOrderDO merge);

    @Select("SELECT * FROM merge_order WHERE id=#{id}")
    MergeOrderDO selectById(String id);

    @Select("SELECT * FROM merge_order WHERE request_id=#{requestId}")
    MergeOrderDO selectByRequestId(String requestId);

    @Insert("INSERT INTO merge_order_rel(merge_id,order_id,amount,fee) VALUES(#{mergeId},#{orderId},#{amount},#{fee})")
    int insertRel(MergeRelDO rel);

    @Select("SELECT * FROM merge_order_rel WHERE merge_id=#{mergeId} ORDER BY order_id")
    List<MergeRelDO> selectRels(String mergeId);

    /** 受理占位：CREATED→PAYING + 绑定支付单/承载单（CAS，与重开/撤销互斥）。 */
    @Update("UPDATE merge_order SET status='PAYING', payment_id=#{paymentId}, carrier_order_id=#{carrierOrderId} "
            + "WHERE id=#{id} AND status='CREATED'")
    int markPaying(@Param("id") String id, @Param("paymentId") String paymentId, @Param("carrierOrderId") String carrierOrderId);

    /** rel fee 分摊快照（受理时一次性落，Σfee=支付单 fee）。 */
    @Update("UPDATE merge_order_rel SET fee=#{fee} WHERE merge_id=#{mergeId} AND order_id=#{orderId}")
    int updateRelFee(@Param("mergeId") String mergeId, @Param("orderId") String orderId, @Param("fee") long fee);

    /** 子订单确认收货计数（MERGE_PAY confirm 内与 markPaid 同事务，只翻一次）。 */
    @Update("UPDATE merge_order SET paid_count=paid_count+1 WHERE id=#{id} AND status='PAYING' AND paid_count<order_count")
    int bumpPaid(@Param("id") String id);

    /** 全部子订单已付 → PAID（CAS，只一次）。 */
    @Update("UPDATE merge_order SET status='PAID', paid_at=#{paidAt} WHERE id=#{id} AND status='PAYING' AND paid_count>=order_count")
    int finishIfAllPaid(@Param("id") String id, @Param("paidAt") long paidAt);

    /** 整体回滚重开：PAYING→CREATED（TCC cancel 内幂等 CAS；paid_count 必为 0）。 */
    @Update("UPDATE merge_order SET status='CREATED', payment_id=NULL WHERE id=#{id} AND status='PAYING' AND payment_id=#{paymentId} AND paid_count=0")
    int reopenOnRollback(@Param("id") String id, @Param("paymentId") String paymentId);

    /** 撤销释放：PAID→CREATED（可重付），清支付单/承载单/计数。 */
    @Update("UPDATE merge_order SET status='CREATED', payment_id=NULL, carrier_order_id=NULL, paid_count=0, paid_at=NULL "
            + "WHERE id=#{id} AND status='PAID' AND payment_id=#{paymentId}")
    int releaseAfterCancel(@Param("id") String id, @Param("paymentId") String paymentId);

    /** 拆分语义：CREATED（未付/已释放）→ DISSOLVED（终态，子订单解绑后可个别支付）。 */
    @Update("UPDATE merge_order SET status='DISSOLVED' WHERE id=#{id} AND status='CREATED' AND paid_count=0")
    int dissolve(@Param("id") String id);
}