package com.qoder.payment.marketing.mapper;

import com.qoder.payment.facade.marketing.MarketingTemplateDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** X-3a 营销模板（券/红包共用）。库存 issued 只经原子条件 UPDATE 累加，绝不超发。 */
@Mapper
public interface MarketingTemplateMapper {

    @Insert("INSERT INTO marketing_template(id,kind,name,amount,threshold,total_stock,issued,per_member_limit,"
            + "valid_days,expire_at,status,rule,operator,created_at,updated_at) VALUES(#{id},#{kind},#{name},"
            + "#{amount},#{threshold},#{totalStock},#{issued},#{perMemberLimit},#{validDays},#{expireAt},"
            + "#{status},#{rule},#{operator},#{createdAt},#{updatedAt})")
    int insert(MarketingTemplateDTO template);

    @Update("UPDATE marketing_template SET name=#{name},amount=#{amount},threshold=#{threshold},"
            + "total_stock=#{totalStock},per_member_limit=#{perMemberLimit},valid_days=#{validDays},"
            + "expire_at=#{expireAt},status=#{status},rule=#{rule},operator=#{operator},updated_at=#{updatedAt} "
            + "WHERE id=#{id}")
    int update(MarketingTemplateDTO template);

    @Select("SELECT * FROM marketing_template WHERE id=#{id}")
    MarketingTemplateDTO selectById(String id);

    @Select("<script>SELECT * FROM marketing_template WHERE 1=1"
            + "<if test='kind != null and kind != \"\"'> AND kind=#{kind}</if>"
            + "<if test='status != null and status != \"\"'> AND status=#{status}</if>"
            + " ORDER BY created_at DESC, id DESC</script>")
    List<MarketingTemplateDTO> selectList(@Param("kind") String kind, @Param("status") String status);

    /**
     * 库存原子扣减：issued + n ≤ total_stock（total_stock ≤ 0 表示不限量）。
     * 返回 1 = 抢占成功，0 = 库存不足/模板非 ACTIVE（并发安全，跨实例不超发）。
     */
    @Update("UPDATE marketing_template SET issued = issued + #{n}, updated_at=#{now} "
            + "WHERE id=#{id} AND status='ACTIVE' AND (total_stock <= 0 OR issued + #{n} <= total_stock)")
    int consumeStock(@Param("id") String id, @Param("n") int n, @Param("now") long now);
}