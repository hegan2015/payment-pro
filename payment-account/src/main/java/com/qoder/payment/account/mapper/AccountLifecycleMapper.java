package com.qoder.payment.account.mapper;

import com.qoder.payment.account.dataobject.AccountLifecycleLogDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** A-5 生命周期留痕（append-only；账户域不分片，单库表）。 */
@Mapper
public interface AccountLifecycleMapper {

    @Select("SELECT next_value_for('seq_account_lifecycle')")
    long nextId();

    @Insert("INSERT INTO account_lifecycle_log(id,account_id,action,from_status,to_status,from_level,to_level,"
            + "operator,reason,biz_no,created_at) VALUES(#{id},#{accountId},#{action},#{fromStatus},#{toStatus},"
            + "#{fromLevel},#{toLevel},#{operator},#{reason},#{bizNo},#{createdAt})")
    int insert(AccountLifecycleLogDO log);

    @Select("SELECT * FROM account_lifecycle_log WHERE account_id=#{accountId} ORDER BY created_at, id")
    List<AccountLifecycleLogDO> selectByAccount(String accountId);

    @Select("SELECT COUNT(*) FROM account_lifecycle_log WHERE account_id=#{accountId} AND action=#{action}")
    int countAction(@Param("accountId") String accountId, @Param("action") String action);
}