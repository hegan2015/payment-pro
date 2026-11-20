package com.qoder.payment.member.mapper;

import com.qoder.payment.member.dataobject.MerchantApplicationDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MerchantApplicationMapper {

    @Select("SELECT next_value_for('seq_application')")
    long nextId();

    @Insert("INSERT INTO merchant_application(id,merchant_name,license_no_hash,license_no_mask,"
            + "legal_person_mask,contact_mask,qualification,status,reject_reason,member_id,submitter,"
            + "created_at,reviewed_at,updated_at) "
            + "VALUES(#{id},#{merchantName},#{licenseNoHash},#{licenseNoMask},#{legalPersonMask},"
            + "#{contactMask},#{qualification},#{status},#{rejectReason},#{memberId},#{submitter},"
            + "#{createdAt},#{reviewedAt},#{updatedAt})")
    int insert(MerchantApplicationDO application);

    @Select("SELECT * FROM merchant_application WHERE id=#{id}")
    MerchantApplicationDO selectById(String id);

    @Select("SELECT * FROM merchant_application WHERE license_no_hash=#{hash}")
    MerchantApplicationDO selectByLicenseHash(String hash);

    @Select("SELECT * FROM merchant_application ORDER BY created_at DESC, id DESC")
    List<MerchantApplicationDO> selectAll();

    @Select("SELECT * FROM merchant_application WHERE status=#{status} ORDER BY created_at DESC, id DESC")
    List<MerchantApplicationDO> selectByStatus(String status);

    @Update("UPDATE merchant_application SET status=#{status},reject_reason=#{rejectReason},"
            + "member_id=#{memberId},reviewed_at=#{reviewedAt},updated_at=#{updatedAt} WHERE id=#{id}")
    int updateReview(MerchantApplicationDO application);

    @Update("UPDATE merchant_application SET merchant_name=#{merchantName},legal_person_mask=#{legalPersonMask},"
            + "contact_mask=#{contactMask},qualification=#{qualification},status=#{status},"
            + "reject_reason=#{rejectReason},updated_at=#{updatedAt} WHERE id=#{id}")
    int updateResubmit(MerchantApplicationDO application);
}