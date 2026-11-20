package com.qoder.payment.member.mapper;

import com.qoder.payment.member.dataobject.MerchantSettleCardDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MerchantSettleCardMapper {

    @Select("SELECT next_value_for('seq_settle_card')")
    long nextId();

    @Insert("INSERT INTO merchant_settle_card(id,merchant_id,card_no_hash,card_no_mask,bank_name,status,created_at) "
            + "VALUES(#{id},#{merchantId},#{cardNoHash},#{cardNoMask},#{bankName},#{status},#{createdAt})")
    int insert(MerchantSettleCardDO card);

    @Select("SELECT * FROM merchant_settle_card WHERE merchant_id=#{merchantId} AND card_no_hash=#{hash}")
    MerchantSettleCardDO selectByMerchantAndHash(@Param("merchantId") String merchantId, @Param("hash") String hash);

    @Select("SELECT * FROM merchant_settle_card WHERE merchant_id=#{merchantId} ORDER BY created_at, id")
    List<MerchantSettleCardDO> selectByMerchantId(String merchantId);

    @Update("UPDATE merchant_settle_card SET status=#{status} WHERE id=#{id}")
    int updateStatus(@Param("id") String id, @Param("status") String status);

    @Select("SELECT COUNT(*) FROM merchant_settle_card WHERE merchant_id=#{merchantId} AND status='DEFAULT'")
    int countDefault(String merchantId);
}