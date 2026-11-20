package com.qoder.payment.payment.mapper;

import com.qoder.payment.payment.dataobject.MarketingLegDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * P-7 营销抵扣明细（additive 新表，单库不分片）。
 *
 * <p>幂等：UNIQUE(payment_id,leg_seq) + UNIQUE(payment_id,source,ref_id)；
 * 核销完成回填 use_id/biz_no/amount 只在 PENDING→USED 时生效（CAS，重放不覆盖）。
 * 退回只翻状态 USED→ROLLED_BACK，<b>绝不删除行、绝不改写金额与凭证号</b>（审计链完整）。
 */
@Mapper
public interface MarketingLegMapper {

    @Select("SELECT next_value_for('seq_marketing_leg')")
    long nextId();

    @Insert("INSERT INTO payment_marketing_leg(id,payment_id,leg_seq,source,ref_id,use_id,biz_no,biz_key,amount,points,"
            + "status,fail_reason,created_at,updated_at) "
            + "VALUES(#{id},#{paymentId},#{legSeq},#{source},#{refId},#{useId},#{bizNo},#{bizKey},#{amount},#{points},"
            + "#{status},#{failReason},#{createdAt},#{updatedAt})")
    int insert(MarketingLegDO row);

    @Select("SELECT * FROM payment_marketing_leg WHERE payment_id=#{paymentId} ORDER BY leg_seq ASC")
    List<MarketingLegDO> selectByPayment(String paymentId);

    @Select("SELECT * FROM payment_marketing_leg WHERE payment_id=#{paymentId} AND source=#{source} AND ref_id=#{refId}")
    MarketingLegDO selectByKey(@Param("paymentId") String paymentId, @Param("source") String source,
                               @Param("refId") String refId);

    /** 核销完成回填（CAS：只 PENDING 可迁 USED，重放不覆盖既有凭证）。 */
    @Update("UPDATE payment_marketing_leg SET status='USED', use_id=#{useId}, biz_no=#{bizNo}, biz_key=#{bizKey}, "
            + "amount=#{amount}, points=#{points}, updated_at=#{now} WHERE id=#{id} AND status='PENDING'")
    int markUsed(@Param("id") String id, @Param("useId") String useId, @Param("bizNo") String bizNo,
                 @Param("bizKey") String bizKey, @Param("amount") long amount, @Param("points") Long points,
                 @Param("now") long now);

    @Update("UPDATE payment_marketing_leg SET status=#{status}, fail_reason=#{failReason}, updated_at=#{now} "
            + "WHERE id=#{id} AND status=#{from}")
    int casStatus(@Param("id") String id, @Param("from") String from, @Param("status") String status,
                  @Param("failReason") String failReason, @Param("now") long now);
}