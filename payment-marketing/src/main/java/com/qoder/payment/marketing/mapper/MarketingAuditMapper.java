package com.qoder.payment.marketing.mapper;

import com.qoder.payment.facade.marketing.MarketingAuditDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** X-3 营销审计留痕（只追加，不可篡改）。 */
@Mapper
public interface MarketingAuditMapper {

    @Insert("INSERT INTO marketing_audit(id,entity_type,entity_id,action,from_status,to_status,amount,"
            + "biz_no,operator,detail,created_at) VALUES(#{id},#{entityType},#{entityId},#{action},"
            + "#{fromStatus},#{toStatus},#{amount},#{bizNo},#{operator},#{detail},#{createdAt})")
    int insert(MarketingAuditDTO row);

    @Select("<script>SELECT * FROM marketing_audit"
            + "<if test='entityId != null and entityId != \"\"'> WHERE entity_id=#{entityId}</if>"
            + " ORDER BY created_at DESC, id DESC LIMIT #{limit}</script>")
    List<MarketingAuditDTO> selectRecent(@Param("entityId") String entityId, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM marketing_audit WHERE entity_id=#{entityId} AND action=#{action}")
    int countByAction(@Param("entityId") String entityId, @Param("action") String action);
}