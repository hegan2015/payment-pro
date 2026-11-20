package com.qoder.payment.channel.mapper;

import com.qoder.payment.channel.dataobject.ChannelReceiptLogDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChannelReceiptLogMapper {

    /** 唯一键 (biz_no,status) 冲突由调用方捕获 DuplicateKeyException 吸收（重复回执）。 */
    @Insert("INSERT INTO channel_receipt_log(biz_no,channel_order_no,status,raw_payload,created_at) "
            + "VALUES(#{bizNo},#{channelOrderNo},#{status},#{rawPayload},#{createdAt})")
    int insert(ChannelReceiptLogDO log);

    @Select("SELECT * FROM channel_receipt_log WHERE biz_no=#{bizNo} ORDER BY created_at ASC, id ASC")
    List<ChannelReceiptLogDO> selectByBizNo(String bizNo);
}