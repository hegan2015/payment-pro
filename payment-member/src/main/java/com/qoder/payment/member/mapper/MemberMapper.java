package com.qoder.payment.member.mapper;

import com.qoder.payment.member.dataobject.MemberDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MemberMapper {

    @Select("SELECT NEXT VALUE FOR seq_member")
    long nextId();

    @Insert("INSERT INTO member(id,name,type,status,app_id,app_secret,created_at) "
            + "VALUES(#{id},#{name},#{type},#{status},#{appId},#{appSecret},#{createdAt})")
    int insert(MemberDO member);

    @Select("SELECT * FROM member WHERE id=#{id}")
    MemberDO selectById(String id);

    @Select("SELECT * FROM member ORDER BY created_at, id")
    List<MemberDO> selectAll();

    @Select("SELECT * FROM member WHERE app_id=#{appId}")
    MemberDO selectByAppId(String appId);

    @Update("UPDATE member SET status=#{status} WHERE id=#{id}")
    int updateStatus(@Param("id") String id, @Param("status") String status);
}