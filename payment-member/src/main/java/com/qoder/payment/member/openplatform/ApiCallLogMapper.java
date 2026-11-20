package com.qoder.payment.member.openplatform;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * G-4a 调用计费账本（只开放平台计量，<b>不写任何账务分录</b>）。
 * 累加式 upsert：先 UPDATE 累加，影响 0 行再 INSERT；唯一键冲突兜底再 UPDATE（H2/MySQL 同一套 SQL）。
 */
@Mapper
public interface ApiCallLogMapper {

    @Select("SELECT next_value_for('seq_openapi_log')")
    long nextId();

    @Insert("INSERT INTO openapi_call_log(id,app_id,api,biz_date,call_count,reject_count,unit_price,billed_amount,updated_at) "
            + "VALUES(#{id},#{appId},#{api},#{bizDate},#{callCount},#{rejectCount},#{unitPrice},#{billedAmount},#{updatedAt})")
    int insert(ApiCallLogDO log);

    /**
     * 增量计价：billed_amount += calls × 本次单价（改价不追溯历史计数）。
     * 赋值顺序保证 MySQL 左到右求值与 H2 旧值语义结果一致（billed_amount 用未累加前的旧值）。
     */
    @Update("UPDATE openapi_call_log SET billed_amount=billed_amount+#{calls}*#{unitPrice}, "
            + "call_count=call_count+#{calls}, reject_count=reject_count+#{rejects}, "
            + "unit_price=#{unitPrice}, updated_at=#{updatedAt} "
            + "WHERE app_id=#{appId} AND api=#{api} AND biz_date=#{bizDate}")
    int accumulate(@Param("appId") String appId, @Param("api") String api, @Param("bizDate") String bizDate,
                   @Param("calls") long calls, @Param("rejects") long rejects,
                   @Param("unitPrice") long unitPrice, @Param("updatedAt") long updatedAt);

    @Select("SELECT * FROM openapi_call_log WHERE app_id=#{appId} AND api=#{api} AND biz_date=#{bizDate}")
    ApiCallLogDO selectOne(@Param("appId") String appId, @Param("api") String api, @Param("bizDate") String bizDate);

    @Select("SELECT * FROM openapi_call_log WHERE app_id=#{appId} AND biz_date=#{bizDate} ORDER BY api")
    List<ApiCallLogDO> selectByAppDate(@Param("appId") String appId, @Param("bizDate") String bizDate);

    @Select("SELECT * FROM openapi_call_log WHERE biz_date=#{bizDate} ORDER BY app_id, api")
    List<ApiCallLogDO> selectByDate(String bizDate);

    @Select("SELECT COALESCE(SUM(call_count),0) FROM openapi_call_log WHERE app_id=#{appId} AND biz_date=#{bizDate}")
    long sumCalls(@Param("appId") String appId, @Param("bizDate") String bizDate);

    @Select("SELECT COALESCE(SUM(reject_count),0) FROM openapi_call_log WHERE app_id=#{appId} AND biz_date=#{bizDate}")
    long sumRejects(@Param("appId") String appId, @Param("bizDate") String bizDate);
}