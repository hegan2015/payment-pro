package com.qoder.payment.account.mapper;

import com.qoder.payment.account.dataobject.FreezeLogDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FreezeLogMapper {

    @Select("SELECT next_value_for('seq_freeze_log')")
    long nextId();

    @Insert("INSERT INTO account_freeze_log(id,account_id,action,freeze_type,reason_type,reason,amount,"
            + "operator,authorized_by,biz_no,created_at) "
            + "VALUES(#{id},#{accountId},#{action},#{freezeType},#{reasonType},#{reason},#{amount},"
            + "#{operator},#{authorizedBy},#{bizNo},#{createdAt})")
    int insert(FreezeLogDO log);

    @Select("SELECT * FROM account_freeze_log WHERE account_id=#{accountId} ORDER BY created_at DESC, id DESC")
    List<FreezeLogDO> selectByAccount(String accountId);
}