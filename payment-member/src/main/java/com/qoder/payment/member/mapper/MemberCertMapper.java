package com.qoder.payment.member.mapper;

import com.qoder.payment.member.dataobject.MemberCertDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 商户应用公钥证书（M-1/S-1）。 */
@Mapper
public interface MemberCertMapper {

    @Select("SELECT next_value_for('seq_member_cert')")
    long nextId();

    @Insert("INSERT INTO member_cert(id,member_id,cert_sn,cert_pem,public_key,not_before,not_after,status,created_at) "
            + "VALUES(#{id},#{memberId},#{certSn},#{certPem},#{publicKey},#{notBefore},#{notAfter},#{status},#{createdAt})")
    int insert(MemberCertDO cert);

    @Select("SELECT * FROM member_cert WHERE member_id=#{memberId} ORDER BY created_at DESC, id DESC")
    List<MemberCertDO> selectByMember(String memberId);

    @Select("SELECT id FROM member_cert WHERE member_id=#{memberId} AND status='ACTIVE' "
            + "ORDER BY created_at DESC, id DESC LIMIT 1")
    String selectLatestActiveId(String memberId);

    @Select("SELECT * FROM member_cert WHERE id=#{id}")
    MemberCertDO selectById(String id);

    @Select("SELECT * FROM member_cert WHERE status='ACTIVE' AND not_after IS NOT NULL AND not_after < #{before} "
            + "ORDER BY not_after")
    List<MemberCertDO> selectExpiring(@Param("before") long before);
}