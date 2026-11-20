package com.qoder.payment.recon.mapper;

import com.qoder.payment.recon.dataobject.ChannelReconDiffDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** RC-3 逐笔差异（SINGLE 表，UNIQUE(channel_code,biz_date,biz_no,diff_type) 重跑去重）。 */
@Mapper
public interface ChannelReconDiffMapper {

    @Select("SELECT next_value_for('seq_channel_diff')")
    long nextId();

    @Insert("INSERT INTO channel_recon_diff(id,channel_code,biz_date,biz_no,channel_order_no,diff_type,"
            + "internal_amount,external_amount,internal_fee,external_fee,internal_status,external_status,"
            + "status,dispute_id,suggestion,created_at,updated_at) "
            + "VALUES(#{id},#{channelCode},#{bizDate},#{bizNo},#{channelOrderNo,jdbcType=VARCHAR},#{diffType},"
            + "#{internalAmount,jdbcType=BIGINT},#{externalAmount,jdbcType=BIGINT},#{internalFee,jdbcType=BIGINT},"
            + "#{externalFee,jdbcType=BIGINT},#{internalStatus,jdbcType=VARCHAR},#{externalStatus,jdbcType=VARCHAR},"
            + "#{status},#{disputeId,jdbcType=BIGINT},#{suggestion,jdbcType=VARCHAR},#{createdAt},#{updatedAt})")
    int insert(ChannelReconDiffDO diff);

    @Select("SELECT * FROM channel_recon_diff WHERE channel_code=#{channelCode} AND biz_date=#{bizDate} "
            + "AND biz_no=#{bizNo} AND diff_type=#{diffType}")
    ChannelReconDiffDO selectUnique(@Param("channelCode") String channelCode, @Param("bizDate") long bizDate,
                                    @Param("bizNo") String bizNo, @Param("diffType") String diffType);

    @Update("UPDATE channel_recon_diff SET channel_order_no=#{channelOrderNo,jdbcType=VARCHAR}, "
            + "internal_amount=#{internalAmount,jdbcType=BIGINT}, external_amount=#{externalAmount,jdbcType=BIGINT}, "
            + "internal_fee=#{internalFee,jdbcType=BIGINT}, external_fee=#{externalFee,jdbcType=BIGINT}, "
            + "internal_status=#{internalStatus,jdbcType=VARCHAR}, external_status=#{externalStatus,jdbcType=VARCHAR}, "
            + "status=#{status}, dispute_id=#{disputeId,jdbcType=BIGINT}, suggestion=#{suggestion,jdbcType=VARCHAR}, "
            + "updated_at=#{updatedAt} WHERE id=#{id}")
    int refresh(ChannelReconDiffDO diff);

    @Select("<script>SELECT * FROM channel_recon_diff "
            + "<where>"
            + "<if test='channelCode != null and channelCode != \"\"'>AND channel_code=#{channelCode} </if>"
            + "<if test='bizDate != null'>AND biz_date=#{bizDate} </if>"
            + "</where>"
            + " ORDER BY biz_date DESC, channel_code, diff_type, biz_no</script>")
    List<ChannelReconDiffDO> selectList(@Param("channelCode") String channelCode, @Param("bizDate") Long bizDate);
}