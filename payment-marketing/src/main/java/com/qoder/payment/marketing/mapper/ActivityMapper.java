package com.qoder.payment.marketing.mapper;

import com.qoder.payment.facade.marketing.ActivityDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * X-3b 活动。<b>预算原子扣减</b>：{@code used + amount <= budget} 的条件 UPDATE，
 * H2/MySQL 同构、跨实例并发安全（等价且强于「local 锁 / docker Redis 计数」，无新增中间件依赖）。
 */
@Mapper
public interface ActivityMapper {

    @Insert("INSERT INTO activity(id,name,type,threshold,discount_amount,discount_bps,max_discount,budget,used,"
            + "status,expire_at,rule,operator,created_at,updated_at) VALUES(#{id},#{name},#{type},#{threshold},"
            + "#{discountAmount},#{discountBps},#{maxDiscount},#{budget},#{used},#{status},#{expireAt},#{rule},"
            + "#{operator},#{createdAt},#{updatedAt})")
    int insert(ActivityDTO activity);

    @Update("UPDATE activity SET name=#{name},type=#{type},threshold=#{threshold},"
            + "discount_amount=#{discountAmount},discount_bps=#{discountBps},max_discount=#{maxDiscount},"
            + "budget=#{budget},status=#{status},expire_at=#{expireAt},rule=#{rule},operator=#{operator},"
            + "updated_at=#{updatedAt} WHERE id=#{id}")
    int update(ActivityDTO activity);

    @Select("SELECT * FROM activity WHERE id=#{id}")
    ActivityDTO selectById(String id);

    @Select("<script>SELECT * FROM activity WHERE status='ACTIVE' AND (expire_at IS NULL OR expire_at&gt;#{now})"
            + "<if test='orderAmount != null'> AND threshold&lt;=#{orderAmount}</if>"
            + " ORDER BY created_at DESC, id DESC</script>")
    List<ActivityDTO> selectActive(@Param("now") long now, @Param("orderAmount") Long orderAmount);

    @Select("SELECT * FROM activity ORDER BY created_at DESC, id DESC")
    List<ActivityDTO> selectAll();

    /** 预算抢占：返回 1=成功，0=预算不足/非 ACTIVE/已过期（绝不超扣）。 */
    @Update("UPDATE activity SET used=used+#{amount}, updated_at=#{now} "
            + "WHERE id=#{id} AND status='ACTIVE' AND (budget<=0 OR used+#{amount}<=budget) "
            + "AND (expire_at IS NULL OR expire_at>#{now})")
    int consumeBudget(@Param("id") String id, @Param("amount") long amount, @Param("now") long now);

    /** 预算回滚（退回）：used ≥ amount 才回滚，绝不为负。 */
    @Update("UPDATE activity SET used=used-#{amount}, updated_at=#{now} WHERE id=#{id} AND used>=#{amount}")
    int releaseBudget(@Param("id") String id, @Param("amount") long amount, @Param("now") long now);
}