package com.qoder.payment.member.mapper;

import com.qoder.payment.member.dataobject.MemberKycDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KycMapper {

    @Select("SELECT next_value_for('seq_kyc')")
    long nextId();

    @Insert("INSERT INTO member_kyc(id,member_id,name_hash,name_mask,id_card_no_hash,id_card_mask,"
            + "bank_card_no_hash,bank_card_mask,mobile_hash,mobile_mask,verify_status,level,reason,"
            + "verified_at,created_at,updated_at) "
            + "VALUES(#{id},#{memberId},#{nameHash},#{nameMask},#{idCardNoHash},#{idCardMask},"
            + "#{bankCardNoHash},#{bankCardMask},#{mobileHash},#{mobileMask},#{verifyStatus},#{level},#{reason},"
            + "#{verifiedAt},#{createdAt},#{updatedAt})")
    int insert(MemberKycDO kyc);

    @Select("SELECT * FROM member_kyc WHERE member_id=#{memberId}")
    MemberKycDO selectByMemberId(String memberId);

    @Update("UPDATE member_kyc SET name_hash=#{nameHash},name_mask=#{nameMask},"
            + "id_card_no_hash=#{idCardNoHash},id_card_mask=#{idCardMask},"
            + "bank_card_no_hash=#{bankCardNoHash},bank_card_mask=#{bankCardMask},"
            + "mobile_hash=#{mobileHash},mobile_mask=#{mobileMask},verify_status=#{verifyStatus},"
            + "level=#{level},reason=#{reason},verified_at=#{verifiedAt},updated_at=#{updatedAt} "
            + "WHERE member_id=#{memberId}")
    int updateByMemberId(MemberKycDO kyc);
}