package com.qoder.payment.settle.mapper;

import com.qoder.payment.facade.settle.FeeRuleDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * ST-2 分层费率规则 + 变更审计（单库不分片配置表，DB 权威）。
 * tiers 列以 JSON 文本持久化，别名 tiers_json → DTO.tiersJson，服务层解析成 tiers。
 */
@Mapper
public interface FeeRuleMapper {

    String COLS = "id,merchant_id,product_type,method,fee_type,fee_bps,tiers AS tiers_json,share_ratio,"
            + "status,version,created_at,updated_at";

    @Select("SELECT " + COLS + " FROM fee_rule WHERE status='ACTIVE' ORDER BY merchant_id,product_type,method")
    List<FeeRuleDTO> selectActive();

    @Select("SELECT " + COLS + " FROM fee_rule WHERE merchant_id=#{merchantId} ORDER BY product_type,method")
    List<FeeRuleDTO> selectByMerchant(String merchantId);

    @Select("SELECT " + COLS + " FROM fee_rule WHERE merchant_id=#{merchantId} AND product_type=#{productType} "
            + "AND method=#{method}")
    FeeRuleDTO selectByKey(@Param("merchantId") String merchantId, @Param("productType") String productType,
                           @Param("method") String method);

    @Insert("INSERT INTO fee_rule(id,merchant_id,product_type,method,fee_type,fee_bps,tiers,share_ratio,"
            + "status,version,created_at,updated_at) VALUES(#{id},#{merchantId},#{productType},#{method},"
            + "#{feeType},#{feeBps},#{tiersJson},#{shareRatio},#{status},#{version},#{createdAt},#{updatedAt})")
    int insert(FeeRuleDTO rule);

    @Update("UPDATE fee_rule SET fee_type=#{feeType}, fee_bps=#{feeBps}, tiers=#{tiersJson}, "
            + "share_ratio=#{shareRatio}, status=#{status}, version=#{version}, updated_at=#{updatedAt} "
            + "WHERE id=#{id}")
    int update(FeeRuleDTO rule);

    @Insert("INSERT INTO fee_rule_audit(id,rule_id,merchant_id,product_type,method,old_value,new_value,"
            + "operator,created_at) VALUES(#{id},#{ruleId},#{merchantId},#{productType},#{method},"
            + "#{oldValue},#{newValue},#{operator},#{createdAt})")
    int insertAudit(@Param("id") String id, @Param("ruleId") String ruleId, @Param("merchantId") String merchantId,
                    @Param("productType") String productType, @Param("method") String method,
                    @Param("oldValue") String oldValue, @Param("newValue") String newValue,
                    @Param("operator") String operator, @Param("createdAt") long createdAt);
}