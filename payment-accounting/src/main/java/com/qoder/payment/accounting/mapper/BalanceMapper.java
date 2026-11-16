package com.qoder.payment.accounting.mapper;

import com.qoder.payment.accounting.dataobject.BalanceDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface BalanceMapper {

    @Select("SELECT * FROM account_balance WHERE account_id=#{accountId} FOR UPDATE")
    BalanceDO selectForUpdate(String accountId);

    @Select("SELECT * FROM account_balance WHERE account_id=#{accountId}")
    BalanceDO select(String accountId);

    @Insert("INSERT INTO account_balance(account_id,balance,frozen,version,updated_at) "
            + "VALUES(#{accountId},#{balance},#{frozen},#{version},#{updatedAt})")
    int insert(BalanceDO balance);

    @Update("UPDATE account_balance SET balance=#{balance}, frozen=#{frozen}, version=version+1, updated_at=#{updatedAt} "
            + "WHERE account_id=#{accountId}")
    int update(BalanceDO balance);

    @Select("SELECT * FROM account_balance ORDER BY account_id")
    List<BalanceDO> selectAll();

    @Select("SELECT COALESCE(SUM(balance),0) FROM account_balance")
    long sumBalance();

    @Select("SELECT COUNT(*) FROM account_balance")
    int count();
}