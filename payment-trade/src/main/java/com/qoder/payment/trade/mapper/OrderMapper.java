package com.qoder.payment.trade.mapper;

import com.qoder.payment.trade.dataobject.OrderDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface OrderMapper {

    @Select("SELECT next_value_for('seq_order')")
    long nextId();

    @Insert("INSERT INTO trade_order(id,merchant_id,merchant_name,out_trade_no,biz_type,subject,amount,status,payment_id,refunded_amount,created_at,paid_at,closed_at,product_type,payee_merchant_id,escrow_status,expire_at) "
            + "VALUES(#{id},#{merchantId},#{merchantName},#{outTradeNo},#{bizType},#{subject},#{amount},#{status},#{paymentId},#{refundedAmount},#{createdAt},#{paidAt},#{closedAt},#{productType},#{payeeMerchantId},#{escrowStatus},#{expireAt})")
    int insert(OrderDO order);

    @Select("SELECT * FROM trade_order WHERE id=#{id}")
    OrderDO selectById(String id);

    @Select("SELECT * FROM trade_order WHERE merchant_id=#{merchantId} AND out_trade_no=#{outTradeNo}")
    OrderDO selectByOutTradeNo(@Param("merchantId") String merchantId, @Param("outTradeNo") String outTradeNo);

    @Select("SELECT * FROM trade_order ORDER BY created_at DESC, id DESC")
    List<OrderDO> selectAll();

    @Update("UPDATE trade_order SET status='PAYING', payment_id=#{paymentId} WHERE id=#{id} AND status='CREATED'")
    int reserve(@Param("id") String id, @Param("paymentId") String paymentId);

    @Update("UPDATE trade_order SET status='PAID', paid_at=#{paidAt} WHERE id=#{id} AND status='PAYING'")
    int markPaid(@Param("id") String id, @Param("paidAt") long paidAt);

    @Update("UPDATE trade_order SET status='CREATED', payment_id=NULL WHERE id=#{id} AND status='PAYING' AND payment_id=#{paymentId}")
    int release(@Param("id") String id, @Param("paymentId") String paymentId);

    @Update("UPDATE trade_order SET status='CLOSED', closed_at=#{closedAt} WHERE id=#{id} AND status='CREATED'")
    int close(@Param("id") String id, @Param("closedAt") long closedAt);

    @Update("UPDATE trade_order SET refunded_amount=#{refundedAmount}, status=#{status} WHERE id=#{id}")
    int updateRefund(@Param("id") String id, @Param("refundedAmount") long refundedAmount, @Param("status") String status);

    // ==================================================================
    // P1 · G 泳道 T-1/T-2/T-4 additive（上方既有方法 SQL 一字未改）
    // 所有状态推进一律 CAS（WHERE 旧态），与支付受理/关闭竞态天然互斥。
    // ==================================================================

    /** T-4 到期未支付候选（CREATED + expire_at 到期）；跨片广播归并，分片切片在 Java 侧按 orderId CRC32。 */
    @Select("SELECT * FROM trade_order WHERE status='CREATED' AND expire_at IS NOT NULL AND expire_at<=#{now} "
            + "ORDER BY expire_at ASC, id ASC LIMIT #{limit}")
    List<OrderDO> selectExpired(@Param("now") long now, @Param("limit") int limit);

    /** T-1 担保到期候选（ESCROW + PAID + HOLD + expire_at 到期）。 */
    @Select("SELECT * FROM trade_order WHERE product_type='ESCROW' AND status='PAID' AND escrow_status='HOLD' "
            + "AND expire_at IS NOT NULL AND expire_at<=#{now} ORDER BY expire_at ASC, id ASC LIMIT #{limit}")
    List<OrderDO> selectEscrowDue(@Param("now") long now, @Param("limit") int limit);

    /**
     * T-4 超时关闭 CAS：仅 CREATED 且 <b>payment_id IS NULL</b>（未被任何支付单预留）才可关。
     * 与 {@link #reserve} 的 {@code WHERE status='CREATED'} 互为排他守卫：谁先谁赢，绝不双写。
     */
    @Update("UPDATE trade_order SET status='CLOSED', closed_at=#{closedAt} "
            + "WHERE id=#{id} AND status='CREATED' AND payment_id IS NULL")
    int closeExpired(@Param("id") String id, @Param("closedAt") long closedAt);

    /**
     * T-4 防悬挂回滚：关闭后发现已受理在途支付单（受理与关闭竞态窗口）→ 只把「本次刚关的」单重开。
     * CAS 三条件（CLOSED + 本次 closed_at + 无预留）保证绝不误改他单/他态。
     */
    @Update("UPDATE trade_order SET status='CREATED', closed_at=NULL "
            + "WHERE id=#{id} AND status='CLOSED' AND payment_id IS NULL AND closed_at=#{closedAt}")
    int reopenExpired(@Param("id") String id, @Param("closedAt") long closedAt);

    /** T-1 支付完结置担保中 + 自动确认期限（只一次：escrow_status IS NULL 守卫）。 */
    @Update("UPDATE trade_order SET escrow_status='HOLD', expire_at=#{expireAt} "
            + "WHERE id=#{id} AND product_type='ESCROW' AND escrow_status IS NULL")
    int holdEscrow(@Param("id") String id, @Param("expireAt") long expireAt);

    /** T-1 确认收货 CAS：HOLD → CONFIRMED/AUTO_CONFIRMED（只一次，重复确认 0 行）。 */
    @Update("UPDATE trade_order SET escrow_status=#{toStatus} WHERE id=#{id} AND escrow_status='HOLD'")
    int confirmEscrow(@Param("id") String id, @Param("toStatus") String toStatus);

    /** T-2 预授权冻结成功：CREATED → AUTHORIZED。 */
    @Update("UPDATE trade_order SET status='AUTHORIZED' WHERE id=#{id} AND status='CREATED'")
    int markAuthorized(@Param("id") String id);

    /** T-2 预授权终态：AUTHORIZED → COMPLETED/CANCELLED。 */
    @Update("UPDATE trade_order SET status=#{status} WHERE id=#{id} AND status='AUTHORIZED'")
    int markPreAuthFinal(@Param("id") String id, @Param("status") String status);

    // ==================================================================
    // P2 · H 泳道 T-5/T-6 additive（上方既有方法 SQL 一字未改）。
    // 所有状态/金额推进一律 CAS（WHERE 旧态/旧值），与受理/关闭/退款竞态互斥。
    // ==================================================================

    /** T-5 入合并占位：仅 CREATED 且无预留/无合并/无阶段计划才可占（任一不满足 0 行）。 */
    @Update("UPDATE trade_order SET merge_id=#{mergeId} WHERE id=#{id} AND status='CREATED' "
            + "AND payment_id IS NULL AND merge_id IS NULL AND COALESCE(stage_count,0)=0")
    int attachMerge(@Param("id") String id, @Param("mergeId") String mergeId);

    /** T-5 合并拆分（DISSOLVED）：子订单解绑（仅 CREATED 未预留）。 */
    @Update("UPDATE trade_order SET merge_id=NULL WHERE id=#{id} AND merge_id=#{mergeId} AND status='CREATED' AND payment_id IS NULL")
    int detachMerge(@Param("id") String id, @Param("mergeId") String mergeId);

    /** T-5 MERGE_PAY Try：子订单 CREATED→PAYING（金额快照守卫，改价竞态 0 行）。 */
    @Update("UPDATE trade_order SET status='PAYING', payment_id=#{paymentId} WHERE id=#{id} "
            + "AND status='CREATED' AND merge_id=#{mergeId} AND amount=#{amount} AND COALESCE(refunded_amount,0)=0")
    int reserveMergeSub(@Param("id") String id, @Param("paymentId") String paymentId,
                        @Param("mergeId") String mergeId, @Param("amount") long amount);

    /** T-5 MERGE_PAY Confirm：PAYING→PAID（payment_id 守卫，只翻一次，返回 1 才计数）。 */
    @Update("UPDATE trade_order SET status='PAID', paid_at=#{paidAt} WHERE id=#{id} AND status='PAYING' AND payment_id=#{paymentId}")
    int markPaidMergeSub(@Param("id") String id, @Param("paymentId") String paymentId, @Param("paidAt") long paidAt);

    /** T-5 MERGE_PAY Cancel：PAYING→CREATED 释放（payment_id 守卫；空回滚 0 行无害）。 */
    @Update("UPDATE trade_order SET status='CREATED', payment_id=NULL WHERE id=#{id} AND status='PAYING' AND payment_id=#{paymentId}")
    int releaseMergeSub(@Param("id") String id, @Param("paymentId") String paymentId);

    /** T-5/T-6 合并撤销后子订单释放：PAID→CREATED 可重付（仅未退且指向本支付单）。 */
    @Update("UPDATE trade_order SET status='CREATED', payment_id=NULL, paid_at=NULL WHERE id=#{id} "
            + "AND status='PAID' AND payment_id=#{paymentId} AND refunded_amount=0")
    int releaseMergeSubAfterCancel(@Param("id") String id, @Param("paymentId") String paymentId);

    /** T-5/T-6 承载单撤销后关闭：PAID→CLOSED（仅未退且指向本支付单）。 */
    @Update("UPDATE trade_order SET status='CLOSED', closed_at=#{closedAt} WHERE id=#{id} "
            + "AND status='PAID' AND payment_id=#{paymentId} AND refunded_amount=0")
    int closePaidAfterCancel(@Param("id") String id, @Param("paymentId") String paymentId, @Param("closedAt") long closedAt);

    /** T-5 阶段计划落库：仅 CREATED 无预留/无合并/无既有计划（CAS 只一次）。 */
    @Update("UPDATE trade_order SET stage_count=#{stageCount} WHERE id=#{id} AND status='CREATED' "
            + "AND payment_id IS NULL AND merge_id IS NULL AND COALESCE(stage_count,0)=0")
    int setStagePlan(@Param("id") String id, @Param("stageCount") int stageCount);

    /** T-5 STAGE_PAY Try：顺序期预留（paid_stage=seq-1 守卫；首期 CREATED，后续期 PAID）。 */
    @Update("UPDATE trade_order SET status='PAYING', payment_id=#{paymentId} WHERE id=#{id} "
            + "AND COALESCE(stage_count,0)>0 AND paid_stage=#{prevStage} AND status IN ('CREATED','PAID') "
            + "AND COALESCE(refunded_amount,0)=0 AND merge_id IS NULL")
    int reserveStage(@Param("id") String id, @Param("paymentId") String paymentId, @Param("prevStage") int prevStage);

    /** T-5 STAGE_PAY Confirm：paid_stage 推进 + paid_amount 累加 + 恒置 PAID（recon 口径：SUCCESS 支付单对应订单 PAID）。 */
    @Update("UPDATE trade_order SET paid_stage=#{stageSeq}, paid_amount=paid_amount+#{amount}, status='PAID', paid_at=#{paidAt} "
            + "WHERE id=#{id} AND status='PAYING' AND payment_id=#{paymentId} AND paid_stage=#{prevStage}")
    int markStagePaid(@Param("id") String id, @Param("paymentId") String paymentId,
                      @Param("stageSeq") int stageSeq, @Param("prevStage") int prevStage,
                      @Param("amount") long amount, @Param("paidAt") long paidAt);

    /** T-5 STAGE_PAY Cancel：释放本期预留，回到首期前 CREATED / 后续期 PAID（paid_stage 不动）。 */
    @Update("UPDATE trade_order SET status=CASE WHEN paid_stage=0 THEN 'CREATED' ELSE 'PAID' END, payment_id=NULL "
            + "WHERE id=#{id} AND status='PAYING' AND payment_id=#{paymentId}")
    int releaseStage(@Param("id") String id, @Param("paymentId") String paymentId);

    /** T-5/T-6 阶段撤销后回退一期（仅最新已付期、未退、指向本支付单；CAS 守卫）。 */
    @Update("UPDATE trade_order SET paid_stage=#{prevStage}, paid_amount=paid_amount-#{amount}, "
            + "status=CASE WHEN #{prevStage}=0 THEN 'CREATED' ELSE 'PAID' END, payment_id=NULL, paid_at=NULL "
            + "WHERE id=#{id} AND status='PAID' AND payment_id=#{paymentId} AND paid_stage=#{stageSeq} AND refunded_amount=0")
    int releaseStageAfterCancel(@Param("id") String id, @Param("paymentId") String paymentId,
                                @Param("stageSeq") int stageSeq, @Param("prevStage") int prevStage, @Param("amount") long amount);

    /** T-6 未支付改价 CAS：仅 CREATED 且无预留/无合并/无阶段计划；original_amount 首次留痕，计数累加。 */
    // MySQL UPDATE 赋值从左到右求值：original_amount 必须在 amount 被改写之前取旧值，否则留痕拿到新价。
    @Update("UPDATE trade_order SET original_amount=COALESCE(original_amount, amount), amount=#{newAmount}, "
            + "price_change_count=price_change_count+1 WHERE id=#{id} AND status='CREATED' "
            + "AND payment_id IS NULL AND merge_id IS NULL AND COALESCE(stage_count,0)=0")
    int changePriceCas(@Param("id") String id, @Param("newAmount") long newAmount);

    /** T-6 已支付改价留痕计数（只累加计数，不动 amount/状态/fee）。 */
    @Update("UPDATE trade_order SET price_change_count=price_change_count+1 WHERE id=#{id}")
    int bumpPriceChangeCount(@Param("id") String id);
}