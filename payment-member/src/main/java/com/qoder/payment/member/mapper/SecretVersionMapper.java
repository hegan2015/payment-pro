package com.qoder.payment.member.mapper;

import com.qoder.payment.member.dataobject.SecretVersionDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 商户密钥多版本（M-1）：UNIQUE(member_id,version)，密文落库。 */
@Mapper
public interface SecretVersionMapper {

    @Insert("INSERT INTO member_secret_version(member_id,version,secret_cipher,status,created_at,retired_at,grace_until) "
            + "VALUES(#{memberId},#{version},#{secretCipher},#{status},#{createdAt},#{retiredAt},#{graceUntil})")
    int insert(SecretVersionDO version);

    @Select("SELECT * FROM member_secret_version WHERE member_id=#{memberId} AND status='ACTIVE' "
            + "ORDER BY version DESC LIMIT 1")
    SecretVersionDO selectActive(String memberId);

    @Select("SELECT * FROM member_secret_version WHERE member_id=#{memberId} ORDER BY version DESC")
    List<SecretVersionDO> selectByMember(String memberId);

    @Select("SELECT * FROM member_secret_version WHERE member_id=#{memberId} AND status='RETIRED' "
            + "AND grace_until IS NOT NULL AND grace_until > #{now} ORDER BY version DESC")
    List<SecretVersionDO> selectInGrace(@Param("memberId") String memberId, @Param("now") long now);

    @Select("SELECT COALESCE(MAX(version),0) FROM member_secret_version WHERE member_id=#{memberId}")
    int maxVersion(String memberId);

    @Update("UPDATE member_secret_version SET status='RETIRED', retired_at=#{retiredAt}, grace_until=#{graceUntil} "
            + "WHERE member_id=#{memberId} AND status='ACTIVE'")
    int retireActive(@Param("memberId") String memberId, @Param("retiredAt") long retiredAt,
                     @Param("graceUntil") long graceUntil);
}