package com.qoder.payment.recon.mapper;

import com.qoder.payment.recon.dataobject.DisputeOrderDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface DisputeOrderMapper {

    @Select("SELECT next_value_for('seq_dispute')")
    long nextId();

    @Insert("INSERT INTO dispute_order(id,dispute_no,type,source,biz_no,payment_id,amount,status,strategy,assignee,reason,fix_biz_no,check_name,biz_date,created_at,updated_at,resolved_at) "
            + "VALUES(#{id},#{disputeNo},#{type},#{source},#{bizNo},#{paymentId},#{amount},#{status},#{strategy},#{assignee},#{reason},#{fixBizNo},#{checkName},#{bizDate},#{createdAt},#{updatedAt},#{resolvedAt})")
    int insert(DisputeOrderDO order);

    @Select("SELECT * FROM dispute_order WHERE id=#{id}")
    DisputeOrderDO selectById(@Param("id") long id);

    /** 未关闭同来源差错单（幂等去重前置查询，checkName 精确匹配）。 */
    @Select("SELECT * FROM dispute_order WHERE source=#{source} AND biz_no=#{bizNo} "
            + "AND ((#{checkName,jdbcType=VARCHAR} IS NULL AND check_name IS NULL) OR check_name=#{checkName,jdbcType=VARCHAR}) "
            + "AND status IN ('OPEN','ASSIGNED','FIXING') ORDER BY id DESC LIMIT 1")
    DisputeOrderDO selectOpenBySourceBiz(@Param("source") String source, @Param("bizNo") String bizNo,
                                         @Param("checkName") String checkName);

    @Select("SELECT * FROM dispute_order WHERE source='RECON' AND check_name=#{checkName} "
            + "AND status IN ('OPEN','ASSIGNED','FIXING') ORDER BY id")
    List<DisputeOrderDO> selectOpenByCheck(@Param("checkName") String checkName);

    @Select("SELECT * FROM dispute_order WHERE status='OPEN' AND strategy IS NOT NULL AND strategy <> 'MANUAL' ORDER BY id LIMIT #{limit}")
    List<DisputeOrderDO> selectOpenAutoFixable(@Param("limit") int limit);

    @Select("<script>SELECT * FROM dispute_order "
            + "<where>"
            + "<if test='status != null and status != \"\"'>AND status=#{status}</if>"
            + "<if test='type != null and type != \"\"'>AND type=#{type}</if>"
            + "<if test='source != null and source != \"\"'>AND source=#{source}</if>"
            + "<if test='bizNo != null and bizNo != \"\"'>AND biz_no=#{bizNo}</if>"
            + "</where>"
            + " ORDER BY id DESC LIMIT #{offset},#{size}</script>")
    List<DisputeOrderDO> selectPage(@Param("status") String status, @Param("type") String type,
                                    @Param("source") String source, @Param("bizNo") String bizNo,
                                    @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM dispute_order "
            + "<where>"
            + "<if test='status != null and status != \"\"'>AND status=#{status}</if>"
            + "<if test='type != null and type != \"\"'>AND type=#{type}</if>"
            + "<if test='source != null and source != \"\"'>AND source=#{source}</if>"
            + "<if test='bizNo != null and bizNo != \"\"'>AND biz_no=#{bizNo}</if>"
            + "</where></script>")
    long countPage(@Param("status") String status, @Param("type") String type,
                   @Param("source") String source, @Param("bizNo") String bizNo);

    /** 状态机 CAS：仅当前状态合法才迁移。 */
    @Update("UPDATE dispute_order SET status=#{to}, updated_at=#{now} WHERE id=#{id} AND status=#{from}")
    int casStatus(@Param("id") long id, @Param("from") String from, @Param("to") String to, @Param("now") long now);

    @Update("UPDATE dispute_order SET assignee=#{assignee}, updated_at=#{now} WHERE id=#{id}")
    int updateAssignee(@Param("id") long id, @Param("assignee") String assignee, @Param("now") long now);

    @Update("UPDATE dispute_order SET status=#{to}, fix_biz_no=#{fixBizNo}, resolved_at=#{now}, updated_at=#{now} "
            + "WHERE id=#{id} AND status=#{from}")
    int casResolved(@Param("id") long id, @Param("from") String from, @Param("to") String to,
                    @Param("fixBizNo") String fixBizNo, @Param("now") long now);

    @Select("SELECT status AS k, COUNT(*) AS c FROM dispute_order GROUP BY status")
    List<Map<String, Object>> countByStatus();

    @Select("SELECT type AS k, COUNT(*) AS c FROM dispute_order GROUP BY type")
    List<Map<String, Object>> countByType();

    @Select("SELECT source AS k, COUNT(*) AS c FROM dispute_order GROUP BY source")
    List<Map<String, Object>> countBySource();

    @Select("SELECT COUNT(*) FROM dispute_order")
    long countAll();
}