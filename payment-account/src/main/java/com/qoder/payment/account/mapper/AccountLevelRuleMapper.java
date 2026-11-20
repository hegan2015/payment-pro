package com.qoder.payment.account.mapper;

import com.qoder.payment.account.dataobject.AccountLevelRuleDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AccountLevelRuleMapper {

    @Insert("INSERT INTO account_level_rule(level,single_limit,daily_limit,balance_limit,require_kyc) "
            + "VALUES(#{level},#{singleLimit},#{dailyLimit},#{balanceLimit},#{requireKyc})")
    int insert(AccountLevelRuleDO rule);

    @Select("SELECT * FROM account_level_rule WHERE level=#{level}")
    AccountLevelRuleDO selectByLevel(String level);
}