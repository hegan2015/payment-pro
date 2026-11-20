package com.qoder.payment.recon.mapper;

import com.qoder.payment.recon.dataobject.ChannelReconRunDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** RC-3 渠道内外对账运行汇总（SINGLE 表，UNIQUE(channel_code,biz_date) 重跑复用行）。 */
@Mapper
public interface ChannelReconRunMapper {

    @Select("SELECT next_value_for('seq_channel_recon')")
    long nextId();

    @Insert("INSERT INTO channel_recon_run(id,channel_code,biz_date,source,bill_id,internal_count,external_count,"
            + "matched_count,long_count,short_count,amount_count,status_count,fee_count,internal_total,external_total,"
            + "net_diff,pass,dispute_count,fee_note,message,created_at,updated_at) "
            + "VALUES(#{id},#{channelCode},#{bizDate},#{source},#{billId,jdbcType=BIGINT},#{internalCount},#{externalCount},"
            + "#{matchedCount},#{longCount},#{shortCount},#{amountCount},#{statusCount},#{feeCount},#{internalTotal},"
            + "#{externalTotal},#{netDiff},#{pass},#{disputeCount},#{feeNote,jdbcType=VARCHAR},#{message,jdbcType=VARCHAR},"
            + "#{createdAt},#{updatedAt})")
    int insert(ChannelReconRunDO run);

    @Select("SELECT * FROM channel_recon_run WHERE channel_code=#{channelCode} AND biz_date=#{bizDate}")
    ChannelReconRunDO selectUnique(@Param("channelCode") String channelCode, @Param("bizDate") long bizDate);

    @Update("UPDATE channel_recon_run SET source=#{source}, bill_id=#{billId,jdbcType=BIGINT}, "
            + "internal_count=#{internalCount}, external_count=#{externalCount}, matched_count=#{matchedCount}, "
            + "long_count=#{longCount}, short_count=#{shortCount}, amount_count=#{amountCount}, "
            + "status_count=#{statusCount}, fee_count=#{feeCount}, internal_total=#{internalTotal}, "
            + "external_total=#{externalTotal}, net_diff=#{netDiff}, pass=#{pass}, dispute_count=#{disputeCount}, "
            + "fee_note=#{feeNote,jdbcType=VARCHAR}, message=#{message,jdbcType=VARCHAR}, updated_at=#{updatedAt} "
            + "WHERE id=#{id}")
    int refresh(ChannelReconRunDO run);

    @Select("<script>SELECT * FROM channel_recon_run "
            + "<where>"
            + "<if test='channelCode != null and channelCode != \"\"'>AND channel_code=#{channelCode} </if>"
            + "<if test='bizDate != null'>AND biz_date=#{bizDate} </if>"
            + "</where>"
            + " ORDER BY biz_date DESC, channel_code</script>")
    List<ChannelReconRunDO> selectList(@Param("channelCode") String channelCode, @Param("bizDate") Long bizDate);
}