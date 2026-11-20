package com.qoder.payment.channel.mapper;

import com.qoder.payment.channel.dataobject.ChannelBillDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** CH-4 对账文件主表（幂等键 UNIQUE(channel_code,biz_date)）。 */
@Mapper
public interface ChannelBillMapper {

    @Insert("INSERT INTO channel_bill(channel_code,biz_date,file_path,total_count,total_amount,status,"
            + "parsed_count,parsed_amount,matched_count,diff_count,dispute_count,message,created_at,parsed_at) "
            + "VALUES(#{channelCode},#{bizDate},#{filePath},#{totalCount},#{totalAmount},#{status},"
            + "#{parsedCount},#{parsedAmount},#{matchedCount},#{diffCount},#{disputeCount},#{message},#{createdAt},#{parsedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ChannelBillDO bill);

    @Select("SELECT * FROM channel_bill WHERE channel_code=#{channelCode} AND biz_date=#{bizDate}")
    ChannelBillDO selectByCodeDate(@Param("channelCode") String channelCode, @Param("bizDate") String bizDate);

    @Select("SELECT * FROM channel_bill WHERE id=#{id}")
    ChannelBillDO selectById(long id);

    @Select("<script>SELECT * FROM channel_bill WHERE 1=1 "
            + "<if test='channelCode != null'> AND channel_code=#{channelCode}</if>"
            + "<if test='bizDate != null'> AND biz_date=#{bizDate}</if>"
            + " ORDER BY id DESC</script>")
    List<ChannelBillDO> selectList(@Param("channelCode") String channelCode, @Param("bizDate") String bizDate);

    @Update("UPDATE channel_bill SET status=#{status}, parsed_count=#{parsedCount}, parsed_amount=#{parsedAmount}, "
            + "total_count=#{totalCount}, total_amount=#{totalAmount}, file_path=#{filePath}, "
            + "message=#{message}, parsed_at=#{parsedAt} WHERE id=#{id}")
    int updateParsed(ChannelBillDO bill);

    @Update("UPDATE channel_bill SET matched_count=#{matchedCount}, diff_count=#{diffCount}, "
            + "dispute_count=#{disputeCount}, message=#{message} WHERE id=#{id}")
    int updateReconciled(ChannelBillDO bill);

    @Update("UPDATE channel_bill SET file_path=#{filePath}, total_count=#{totalCount}, total_amount=#{totalAmount}, "
            + "status=#{status}, message=#{message} WHERE id=#{id}")
    int updateDownloaded(ChannelBillDO bill);
}