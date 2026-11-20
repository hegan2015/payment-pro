package com.qoder.payment.member.mapper;

import com.qoder.payment.member.dataobject.UserCredentialDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserCredentialMapper {

    @Insert("INSERT INTO user_credential(member_id,mobile_hash,mobile_mask,password_hash,fail_count,"
            + "locked_until,created_at,updated_at) "
            + "VALUES(#{memberId},#{mobileHash},#{mobileMask},#{passwordHash},#{failCount},"
            + "#{lockedUntil},#{createdAt},#{updatedAt})")
    int insert(UserCredentialDO credential);

    @Select("SELECT * FROM user_credential WHERE member_id=#{memberId}")
    UserCredentialDO selectByMemberId(String memberId);

    @Select("SELECT * FROM user_credential WHERE mobile_hash=#{mobileHash}")
    UserCredentialDO selectByMobileHash(String mobileHash);

    @Update("UPDATE user_credential SET password_hash=#{passwordHash},fail_count=#{failCount},"
            + "locked_until=#{lockedUntil},updated_at=#{updatedAt} WHERE member_id=#{memberId}")
    int update(UserCredentialDO credential);

    @Update("UPDATE user_credential SET fail_count=#{failCount},locked_until=#{lockedUntil},"
            + "updated_at=#{updatedAt} WHERE member_id=#{memberId}")
    int updateFail(@Param("memberId") String memberId, @Param("failCount") int failCount,
                   @Param("lockedUntil") long lockedUntil, @Param("updatedAt") long updatedAt);
}