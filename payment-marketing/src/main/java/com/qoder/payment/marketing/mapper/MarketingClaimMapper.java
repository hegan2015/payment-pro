package com.qoder.payment.marketing.mapper;

import com.qoder.payment.marketing.dataobject.MarketingClaimDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** X-3a 领取留痕：UNIQUE(template_id,member_id,biz_no) 幂等 + 每人限领计数。 */
@Mapper
public interface MarketingClaimMapper {

    @Insert("INSERT INTO marketing_claim(id,template_id,member_id,kind,voucher_id,biz_no,created_at) "
            + "VALUES(#{id},#{templateId},#{memberId},#{kind},#{voucherId},#{bizNo},#{createdAt})")
    int insert(MarketingClaimDO row);

    @Select("SELECT * FROM marketing_claim WHERE template_id=#{templateId} AND member_id=#{memberId} "
            + "AND biz_no=#{bizNo}")
    MarketingClaimDO selectByKey(@Param("templateId") String templateId, @Param("memberId") String memberId,
                                 @Param("bizNo") String bizNo);

    @Select("SELECT COUNT(*) FROM marketing_claim WHERE template_id=#{templateId} AND member_id=#{memberId}")
    int countByMember(@Param("templateId") String templateId, @Param("memberId") String memberId);
}