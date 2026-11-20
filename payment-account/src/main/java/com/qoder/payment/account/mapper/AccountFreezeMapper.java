package com.qoder.payment.account.mapper;

import com.qoder.payment.account.dataobject.AccountFreezeDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AccountFreezeMapper {

    @Select("SELECT next_value_for('seq_freeze')")
    long nextId();

    @Insert("INSERT INTO account_freeze(id,account_id,amount,reason_type,reason,status,operator,"
            + "authorized_by,biz_no,created_at,released_at,release_biz_no) "
            + "VALUES(#{id},#{accountId},#{amount},#{reasonType},#{reason},#{status},#{operator},"
            + "#{authorizedBy},#{bizNo},#{createdAt},#{releasedAt},#{releaseBizNo})")
    int insert(AccountFreezeDO freeze);

    @Select("SELECT * FROM account_freeze WHERE id=#{id}")
    AccountFreezeDO selectById(String id);

    @Select("SELECT * FROM account_freeze WHERE biz_no=#{bizNo}")
    AccountFreezeDO selectByBizNo(String bizNo);

    @Select("SELECT * FROM account_freeze WHERE account_id=#{accountId} AND reason_type=#{reasonType} "
            + "AND status='ACTIVE' ORDER BY created_at, id LIMIT 1")
    AccountFreezeDO selectActiveByAccountAndReason(@Param("accountId") String accountId,
                                                   @Param("reasonType") String reasonType);

    @Select("SELECT * FROM account_freeze WHERE account_id=#{accountId} ORDER BY created_at DESC, id DESC")
    List<AccountFreezeDO> selectByAccount(String accountId);

    @Select("SELECT * FROM account_freeze WHERE account_id=#{accountId} AND status=#{status} "
            + "ORDER BY created_at DESC, id DESC")
    List<AccountFreezeDO> selectByAccountAndStatus(@Param("accountId") String accountId,
                                                   @Param("status") String status);

    /** 释放 CAS：仅 ACTIVE 可释放，防并发双解冻。 */
    @Update("UPDATE account_freeze SET status='RELEASED',released_at=#{releasedAt},"
            + "release_biz_no=#{releaseBizNo},authorized_by=#{authorizedBy} "
            + "WHERE id=#{id} AND status='ACTIVE'")
    int release(@Param("id") String id, @Param("releasedAt") long releasedAt,
                @Param("releaseBizNo") String releaseBizNo, @Param("authorizedBy") String authorizedBy);
}