package com.qoder.payment.member.mapper;

import com.qoder.payment.member.dataobject.MerchantContractDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MerchantContractMapper {

    @Select("SELECT next_value_for('seq_contract')")
    long nextId();

    @Insert("INSERT INTO merchant_contract(id,application_id,merchant_id,products,fee_bps,status,operator,signed_at) "
            + "VALUES(#{id},#{applicationId},#{merchantId},#{products},#{feeBps},#{status},#{operator},#{signedAt})")
    int insert(MerchantContractDO contract);

    @Select("SELECT * FROM merchant_contract WHERE application_id=#{applicationId}")
    MerchantContractDO selectByApplicationId(String applicationId);

    @Select("SELECT * FROM merchant_contract WHERE merchant_id=#{merchantId}")
    MerchantContractDO selectByMerchantId(String merchantId);

    /** UI additive（只读）：合同列表，按签约时间倒序；merchantId 为空则全量（上限 500）。 */
    @Select("<script>SELECT * FROM merchant_contract"
            + "<if test='merchantId != null and merchantId != \"\"'> WHERE merchant_id=#{merchantId}</if>"
            + " ORDER BY signed_at DESC LIMIT 500</script>")
    java.util.List<MerchantContractDO> selectList(@org.apache.ibatis.annotations.Param("merchantId") String merchantId);

    /** UI additive（只读）：合同详情。 */
    @Select("SELECT * FROM merchant_contract WHERE id=#{id}")
    MerchantContractDO selectById(String id);
}