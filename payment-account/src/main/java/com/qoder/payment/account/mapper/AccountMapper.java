package com.qoder.payment.account.mapper;

import com.qoder.payment.account.dataobject.AccountDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AccountMapper {

    @Select("SELECT NEXT VALUE FOR seq_account")
    long nextId();

    @Insert("INSERT INTO account(id,member_id,member_name,type,status,created_at) "
            + "VALUES(#{id},#{memberId},#{memberName},#{type},#{status},#{createdAt})")
    int insert(AccountDO account);

    @Select("SELECT * FROM account WHERE id=#{id}")
    AccountDO selectById(String id);

    @Select("SELECT * FROM account ORDER BY created_at, id")
    List<AccountDO> selectAll();

    @Select("SELECT * FROM account WHERE member_id=#{memberId}")
    AccountDO selectByMemberId(String memberId);

    @Update("UPDATE account SET status=#{status} WHERE id=#{id}")
    int updateStatus(@Param("id") String id, @Param("status") String status);
}