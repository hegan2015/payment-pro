package com.qoder.payment.recon.mapper;

import com.qoder.payment.recon.dataobject.ReconReportDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ReconReportMapper {

    /** 单库取号（payment.sharding.enabled=false 时唯一取号路径，行为逐字节不变）。 */
    @Select("SELECT next_value_for('seq_recon')")
    long nextId();

    @Insert("INSERT INTO recon_report(id,checked_at,pass,report,biz_date,recon_type,channel_code) "
            + "VALUES(#{id},#{checkedAt},#{pass},#{report},#{bizDate,jdbcType=BIGINT},#{reconType,jdbcType=VARCHAR},#{channelCode,jdbcType=VARCHAR})")
    int insert(ReconReportDO report);

    @Select("SELECT * FROM recon_report ORDER BY checked_at DESC, id DESC LIMIT 1")
    ReconReportDO selectLatest();

    /** RC-4 单报告全文（分片键 id 等值 → 单片路由）。 */
    @Select("SELECT * FROM recon_report WHERE id=#{id}")
    ReconReportDO selectById(@Param("id") String id);

    /**
     * RC-4 报告元数据跨片分页（additive，只读，不取 report 大字段）：
     * ORDER BY 带唯一 tiebreaker(id)，投影含全部排序列，ShardingSphere 归并引擎
     * 「各片 LIMIT 0,offset+size → 归并排序 → 跳过 offset」不重不漏（与 selectPage 同口径）。
     * 顶层不做任何 SUM/聚合函数（I-5a/b 踩坑：藏进函数的聚合会被当普通投影返回多行）。
     */
    @Select("<script>SELECT id,checked_at,pass,biz_date,recon_type,channel_code FROM recon_report "
            + "<where>"
            + "<if test='bizDate != null'>AND biz_date=#{bizDate} </if>"
            + "<if test='reconType != null and reconType != \"\"'>AND recon_type=#{reconType} </if>"
            + "</where>"
            + " ORDER BY checked_at DESC, id DESC LIMIT #{offset},#{size}</script>")
    List<ReconReportDO> selectMetaPage(@Param("bizDate") Long bizDate, @Param("reconType") String reconType,
                                       @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM recon_report "
            + "<where>"
            + "<if test='bizDate != null'>AND biz_date=#{bizDate} </if>"
            + "<if test='reconType != null and reconType != \"\"'>AND recon_type=#{reconType} </if>"
            + "</where></script>")
    long countMeta(@Param("bizDate") Long bizDate, @Param("reconType") String reconType);

    /** RC-4 汇总用全量元数据（无大字段；数据量=对账次数，可控）。 */
    @Select("SELECT id,checked_at,pass,biz_date,recon_type,channel_code FROM recon_report ORDER BY checked_at DESC, id DESC")
    List<ReconReportDO> selectAllMeta();

    /**
     * I-5a 跨片分页（additive，只读）：ORDER BY 带唯一 tiebreaker(id)，
     * ShardingSphere 归并引擎按「各片 LIMIT 0,offset+size → 归并排序 → 跳过 offset」处理，
     * 保证不重不漏。投影含全部排序列，两端（H2 单表 / MySQL 分片）SQL 通用。
     */
    @Select("SELECT id,checked_at,pass FROM recon_report ORDER BY checked_at DESC, id DESC LIMIT #{offset},#{size}")
    List<ReconReportDO> selectPage(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM recon_report")
    long countAll();
}