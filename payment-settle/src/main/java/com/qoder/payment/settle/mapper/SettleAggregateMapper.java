package com.qoder.payment.settle.mapper;

import com.qoder.payment.facade.settle.SettleRecordDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * ST-1/ST-5 结算单聚合读取（<b>逻辑表 settle_record 已按 merchant_id 分 4 片</b>）。
 *
 * <p><b>跨片红线（I-5a/b 已踩坑）</b>：SUM 必须是<b>顶层投影</b>，不得藏进 COALESCE 等函数，
 * 否则 ShardingSphere 归并引擎把它当普通投影只取首片值 → 净额失真。空表 SUM 返回 NULL，
 * 由服务层统一 nullToZero，单库/分片两端结果完全一致。
 *
 * <p>带 merchant_id 的查询精确路由到单片；不带 shard key 的扫描由归并引擎拼接各片结果，
 * 去重/分组在 Java 侧完成（不依赖跨片 DISTINCT/GROUP BY 表达式）。
 */
@Mapper
public interface SettleAggregateMapper {

    /** 窗口内 (merchant_id, created_at) 原始投影：Java 侧去重商户 + 按自然日分桶。 */
    @Select("SELECT merchant_id, created_at FROM settle_record WHERE created_at>=#{from} AND created_at<#{to}")
    List<Map<String, Object>> merchantDays(@Param("from") long from, @Param("to") long to);

    @Select("SELECT id,type,biz_id,payment_id,order_id,merchant_id,gross,fee,net,method,status,created_at "
            + "FROM settle_record WHERE merchant_id=#{merchantId} AND created_at>=#{from} AND created_at<#{to} "
            + "ORDER BY created_at, id")
    List<SettleRecordDTO> selectRange(@Param("merchantId") String merchantId,
                                      @Param("from") long from, @Param("to") long to);

    @Select("SELECT COUNT(*) FROM settle_record WHERE merchant_id=#{merchantId} "
            + "AND created_at>=#{from} AND created_at<#{to}")
    int countRange(@Param("merchantId") String merchantId, @Param("from") long from, @Param("to") long to);

    @Select("SELECT SUM(gross) FROM settle_record WHERE merchant_id=#{merchantId} AND type='PAY' "
            + "AND created_at>=#{from} AND created_at<#{to}")
    Long sumPayGross(@Param("merchantId") String merchantId, @Param("from") long from, @Param("to") long to);

    @Select("SELECT SUM(gross) FROM settle_record WHERE merchant_id=#{merchantId} AND type='REFUND' "
            + "AND created_at>=#{from} AND created_at<#{to}")
    Long sumRefundGross(@Param("merchantId") String merchantId, @Param("from") long from, @Param("to") long to);

    @Select("SELECT SUM(CASE WHEN type='PAY' THEN fee ELSE -fee END) FROM settle_record "
            + "WHERE merchant_id=#{merchantId} AND created_at>=#{from} AND created_at<#{to}")
    Long sumFee(@Param("merchantId") String merchantId, @Param("from") long from, @Param("to") long to);

    @Select("SELECT SUM(CASE WHEN type='PAY' THEN net ELSE -net END) FROM settle_record "
            + "WHERE merchant_id=#{merchantId} AND created_at>=#{from} AND created_at<#{to}")
    Long sumNet(@Param("merchantId") String merchantId, @Param("from") long from, @Param("to") long to);
}