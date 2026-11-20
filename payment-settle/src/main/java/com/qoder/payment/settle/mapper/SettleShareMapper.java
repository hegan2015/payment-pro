package com.qoder.payment.settle.mapper;

import com.qoder.payment.facade.settle.ShareSettleDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** ST-3 分账结算明细（单库不分片：唯一键 (share_id,receiver,type) 非 merchant 维度）。 */
@Mapper
public interface SettleShareMapper {

    @Insert("INSERT INTO settle_share_detail(id,share_id,payment_id,merchant_id,merchant_account_id,"
            + "receiver_account_id,amount,fee,net,type,status,biz_no,created_at) "
            + "VALUES(#{id},#{shareId},#{paymentId},#{merchantId},#{merchantAccountId},#{receiverAccountId},"
            + "#{amount},#{fee},#{net},#{type},#{status},#{bizNo},#{createdAt})")
    int insert(ShareSettleDTO detail);

    @Select("SELECT * FROM settle_share_detail WHERE share_id=#{shareId} ORDER BY created_at, id")
    List<ShareSettleDTO> selectByShare(String shareId);

    @Select("SELECT * FROM settle_share_detail WHERE share_id=#{shareId} AND receiver_account_id=#{receiver} "
            + "AND type=#{type}")
    ShareSettleDTO selectByKey(@Param("shareId") String shareId, @Param("receiver") String receiver,
                               @Param("type") String type);

    @Select("SELECT * FROM settle_share_detail WHERE merchant_id=#{merchantId} "
            + "AND created_at>=#{from} AND created_at<#{to} ORDER BY created_at, id")
    List<ShareSettleDTO> selectRange(@Param("merchantId") String merchantId,
                                     @Param("from") long from, @Param("to") long to);

    @Select("SELECT SUM(amount) FROM settle_share_detail WHERE share_id=#{shareId} AND type=#{type}")
    Long sumAmount(@Param("shareId") String shareId, @Param("type") String type);

    @Select("SELECT SUM(fee) FROM settle_share_detail WHERE share_id=#{shareId} AND type=#{type}")
    Long sumFee(@Param("shareId") String shareId, @Param("type") String type);

    @Select("SELECT SUM(net) FROM settle_share_detail WHERE share_id=#{shareId} AND type=#{type}")
    Long sumNet(@Param("shareId") String shareId, @Param("type") String type);

    @Select("SELECT COUNT(*) FROM settle_share_detail WHERE share_id=#{shareId}")
    int countByShare(String shareId);
}