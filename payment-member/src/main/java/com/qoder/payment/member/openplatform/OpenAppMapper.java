package com.qoder.payment.member.openplatform;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** G-4a 开放平台应用表（additive 新表，不动 member 既有表一行）。 */
@Mapper
public interface OpenAppMapper {

    @Insert("INSERT INTO openapi_app(app_id,merchant_id,name,scopes,rate_limit,daily_quota,unit_price,sandbox,"
            + "status,api_version,secret_cipher,secret_fingerprint,operator,created_at,updated_at,revoked_at) "
            + "VALUES(#{appId},#{merchantId},#{name},#{scopes},#{rateLimit},#{dailyQuota},#{unitPrice},#{sandbox},"
            + "#{status},#{apiVersion},#{secretCipher},#{secretFingerprint},#{operator},#{createdAt},#{updatedAt},#{revokedAt})")
    int insert(OpenAppDO app);

    @Select("SELECT * FROM openapi_app WHERE app_id=#{appId}")
    OpenAppDO selectByAppId(String appId);

    @Select("SELECT * FROM openapi_app ORDER BY created_at, app_id")
    List<OpenAppDO> selectAll();

    @Update("UPDATE openapi_app SET name=#{name},scopes=#{scopes},rate_limit=#{rateLimit},daily_quota=#{dailyQuota},"
            + "unit_price=#{unitPrice},api_version=#{apiVersion},operator=#{operator},updated_at=#{updatedAt} "
            + "WHERE app_id=#{appId}")
    int updatePolicy(OpenAppDO app);

    @Update("UPDATE openapi_app SET status=#{status},operator=#{operator},updated_at=#{updatedAt},revoked_at=#{revokedAt} "
            + "WHERE app_id=#{appId}")
    int updateStatus(@Param("appId") String appId, @Param("status") String status,
                     @Param("operator") String operator, @Param("updatedAt") long updatedAt,
                     @Param("revokedAt") Long revokedAt);
}