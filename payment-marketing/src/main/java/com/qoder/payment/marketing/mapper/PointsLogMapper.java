package com.qoder.payment.marketing.mapper;

import com.qoder.payment.facade.marketing.PointsLogDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** X-3b 积分流水（只追加；UNIQUE(biz_no,type) 幂等）。 */
@Mapper
public interface PointsLogMapper {

    @Insert("INSERT INTO points_log(id,member_id,change_amount,balance_after,frozen_after,type,biz_no,ref_id,"
            + "fund_biz_no,account_id,amount,operator,created_at) VALUES(#{id},#{memberId},#{changeAmount},"
            + "#{balanceAfter},#{frozenAfter},#{type},#{bizNo},#{refId},#{fundBizNo},#{accountId},#{amount},"
            + "#{operator},#{createdAt})")
    int insert(PointsLogDTO log);

    @Select("SELECT * FROM points_log WHERE biz_no=#{bizNo} AND type=#{type}")
    PointsLogDTO selectByKey(@Param("bizNo") String bizNo, @Param("type") String type);

    @Select("SELECT * FROM points_log WHERE biz_no=#{bizNo} ORDER BY created_at, id")
    List<PointsLogDTO> selectByBiz(String bizNo);

    @Select("SELECT * FROM points_log WHERE member_id=#{memberId} ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<PointsLogDTO> selectByMember(@Param("memberId") String memberId, @Param("limit") int limit);

    /** 资金侧关联查询：按账务凭证号取积分流水（fundCheck/审计用）。 */
    @Select("SELECT * FROM points_log WHERE fund_biz_no=#{fundBizNo}")
    PointsLogDTO selectByFundBiz(String fundBizNo);

    @Select("SELECT COALESCE(SUM(amount),0) FROM points_log WHERE type=#{type} AND amount IS NOT NULL")
    long sumAmountByType(String type);
}