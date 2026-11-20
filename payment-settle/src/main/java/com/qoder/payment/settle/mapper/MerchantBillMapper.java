package com.qoder.payment.settle.mapper;

import com.qoder.payment.facade.settle.MerchantBillDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** ST-5 商户账单（单库不分片；金额口径来自已分片 settle_record 的顶层 SUM 归并）。 */
@Mapper
public interface MerchantBillMapper {

    @Insert("INSERT INTO merchant_bill(id,merchant_id,biz_date,bill_type,gross,fee,net,refund,share,"
            + "record_count,file_path,status,created_at) VALUES(#{id},#{merchantId},#{bizDate},#{billType},"
            + "#{gross},#{fee},#{net},#{refund},#{share},#{recordCount},#{filePath},#{status},#{createdAt})")
    int insert(MerchantBillDTO bill);

    @Select("SELECT * FROM merchant_bill WHERE id=#{id}")
    MerchantBillDTO selectById(String id);

    @Select("SELECT * FROM merchant_bill WHERE merchant_id=#{merchantId} AND biz_date=#{bizDate} "
            + "AND bill_type=#{billType}")
    MerchantBillDTO selectByKey(@Param("merchantId") String merchantId, @Param("bizDate") long bizDate,
                                @Param("billType") String billType);

    @Select("SELECT * FROM merchant_bill WHERE merchant_id=#{merchantId} ORDER BY biz_date DESC, bill_type, id")
    List<MerchantBillDTO> selectByMerchant(String merchantId);

    @Select("SELECT * FROM merchant_bill ORDER BY biz_date DESC, merchant_id, bill_type, id")
    List<MerchantBillDTO> selectAll();
}