package com.qoder.payment.channel.mapper;

import com.qoder.payment.channel.dataobject.ChannelBillDetailDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** CH-4 对账明细（幂等键 UNIQUE(bill_id,channel_order_no)，重复下载由 DuplicateKeyException 吸收后刷新）。 */
@Mapper
public interface ChannelBillDetailMapper {

    @Insert("INSERT INTO channel_bill_detail(bill_id,channel_order_no,biz_no,type,amount,status,raw,match_result,dispute_id,present) "
            + "VALUES(#{billId},#{channelOrderNo},#{bizNo},#{type},#{amount},#{status},#{raw},#{matchResult},#{disputeId},#{present})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ChannelBillDetailDO detail);

    @Select("SELECT * FROM channel_bill_detail WHERE bill_id=#{billId} ORDER BY id ASC")
    List<ChannelBillDetailDO> selectByBillId(long billId);

    /** 本次账单存在的明细（重复下载后以最新文件为准，历史行标记 present=0 而非删除）。 */
    @Select("SELECT * FROM channel_bill_detail WHERE bill_id=#{billId} AND present=1 ORDER BY id ASC")
    List<ChannelBillDetailDO> selectPresent(long billId);

    @Select("SELECT * FROM channel_bill_detail WHERE bill_id=#{billId} AND channel_order_no=#{channelOrderNo}")
    ChannelBillDetailDO selectByOrderNo(@Param("billId") long billId, @Param("channelOrderNo") String channelOrderNo);

    @Update("UPDATE channel_bill_detail SET match_result=#{matchResult}, dispute_id=#{disputeId} WHERE id=#{id}")
    int updateMatch(@Param("id") long id, @Param("matchResult") String matchResult, @Param("disputeId") Long disputeId);

    @Update("UPDATE channel_bill_detail SET present=0 WHERE bill_id=#{billId}")
    int markAbsent(long billId);

    @Update("UPDATE channel_bill_detail SET biz_no=#{bizNo}, type=#{type}, amount=#{amount}, status=#{status}, "
            + "raw=#{raw}, present=1 WHERE id=#{id}")
    int refresh(ChannelBillDetailDO detail);
}