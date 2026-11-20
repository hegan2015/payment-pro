package com.qoder.payment.member.mapper;

import com.qoder.payment.member.dataobject.SmsCodeDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SmsCodeMapper {

    @Select("SELECT next_value_for('seq_sms')")
    long nextId();

    @Insert("INSERT INTO member_sms_code(id,mobile_hash,code_hash,expires_at,used,created_at) "
            + "VALUES(#{id},#{mobileHash},#{codeHash},#{expiresAt},#{used},#{createdAt})")
    int insert(SmsCodeDO code);

    @Select("SELECT * FROM member_sms_code WHERE mobile_hash=#{mobileHash} AND used=0 "
            + "ORDER BY created_at DESC, id DESC LIMIT 1")
    SmsCodeDO selectLatestUnused(String mobileHash);

    @Select("SELECT * FROM member_sms_code WHERE mobile_hash=#{mobileHash} "
            + "ORDER BY created_at DESC, id DESC LIMIT 1")
    SmsCodeDO selectLatest(String mobileHash);

    @Update("UPDATE member_sms_code SET used=1 WHERE id=#{id} AND used=0")
    int markUsed(@Param("id") String id);
}