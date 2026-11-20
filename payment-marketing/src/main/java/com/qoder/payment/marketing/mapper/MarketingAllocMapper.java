package com.qoder.payment.marketing.mapper;

import com.qoder.payment.facade.marketing.AllocItemDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * X-3b 分摊明细（只追加）。SOURCE 维度由各核销动作自动落行，TARGET 维度由 allocate 落行。
 * UNIQUE(payment_id,dimension,source,target_id,type) + UNIQUE(biz_no) 双重防重 ⇒ 重放不产生重复分摊行。
 */
@Mapper
public interface MarketingAllocMapper {

    @Insert("INSERT INTO marketing_alloc(id,payment_id,dimension,source,target_id,amount,type,biz_no,created_at) "
            + "VALUES(#{id},#{paymentId},#{dimension},#{source},#{targetId},#{amount},#{type},#{bizNo},#{createdAt})")
    int insert(AllocItemDTO item);

    @Select("SELECT * FROM marketing_alloc WHERE biz_no=#{bizNo}")
    AllocItemDTO selectByBiz(String bizNo);

    @Select("SELECT * FROM marketing_alloc WHERE payment_id=#{paymentId} ORDER BY dimension, source, id")
    List<AllocItemDTO> selectByPayment(String paymentId);

    @Select("SELECT * FROM marketing_alloc WHERE payment_id=#{paymentId} AND dimension=#{dimension} "
            + "ORDER BY source, id")
    List<AllocItemDTO> selectByDimension(@Param("paymentId") String paymentId, @Param("dimension") String dimension);

    @Select("SELECT COALESCE(SUM(amount),0) FROM marketing_alloc WHERE payment_id=#{paymentId} "
            + "AND dimension=#{dimension} AND type=#{type}")
    long sum(@Param("paymentId") String paymentId, @Param("dimension") String dimension, @Param("type") String type);

    @Select("SELECT COALESCE(SUM(amount),0) FROM marketing_alloc WHERE dimension=#{dimension} AND type=#{type}")
    long sumAll(@Param("dimension") String dimension, @Param("type") String type);

    @Select("SELECT COUNT(*) FROM marketing_alloc WHERE payment_id=#{paymentId} AND dimension='TARGET'")
    int countTargets(String paymentId);

    /**
     * P-7 退回后目标维度重分摊（additive）：只收敛<b>目标维度 REFUND 调整行</b>的金额，
     * 使 Σ目标净额 = Σ来源净额（diff=0）。
     *
     * <p><b>绝不触碰</b>：SOURCE 维度全部行（与资金凭证 1:1 的审计链）、TARGET 维度 USE 行（原始分摊事实）。
     * 调整行由 reallocate 自己创建（biz_no 前缀 {@code AL:TR:}），幂等：同净额重放金额不变、不产生新行。
     */
    @Update("UPDATE marketing_alloc SET amount=#{amount} WHERE biz_no=#{bizNo} AND dimension='TARGET' AND type='REFUND'")
    int updateTargetAdjust(@Param("bizNo") String bizNo, @Param("amount") long amount);
}