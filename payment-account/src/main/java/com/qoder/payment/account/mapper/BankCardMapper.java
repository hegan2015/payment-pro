package com.qoder.payment.account.mapper;

import com.qoder.payment.account.dataobject.BankCardDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface BankCardMapper {

    @Select("SELECT next_value_for('seq_card')")
    long nextId();

    @Insert("INSERT INTO bank_card(id,account_id,card_no_hash,card_no_mask,bank_name,mobile_hash,mobile_mask,"
            + "name_mask,status,is_default,sign_no,created_at,unbound_at) "
            + "VALUES(#{id},#{accountId},#{cardNoHash},#{cardNoMask},#{bankName},#{mobileHash},#{mobileMask},"
            + "#{nameMask},#{status},#{isDefault},#{signNo},#{createdAt},#{unboundAt})")
    int insert(BankCardDO card);

    @Select("SELECT * FROM bank_card WHERE id=#{id}")
    BankCardDO selectById(String id);

    @Select("SELECT * FROM bank_card WHERE account_id=#{accountId} AND card_no_hash=#{hash}")
    BankCardDO selectByAccountAndHash(@Param("accountId") String accountId, @Param("hash") String hash);

    @Select("SELECT * FROM bank_card WHERE account_id=#{accountId} ORDER BY created_at, id")
    List<BankCardDO> selectByAccount(String accountId);

    @Select("SELECT COUNT(*) FROM bank_card WHERE account_id=#{accountId} AND status='BOUND' AND is_default=1")
    int countDefault(String accountId);

    @Update("UPDATE bank_card SET status=#{status},is_default=#{isDefault},sign_no=#{signNo},"
            + "unbound_at=#{unboundAt} WHERE id=#{id}")
    int updateBinding(BankCardDO card);

    @Update("UPDATE bank_card SET is_default=0 WHERE account_id=#{accountId} AND is_default=1")
    int clearDefault(String accountId);

    @Update("UPDATE bank_card SET is_default=1 WHERE id=#{id} AND status='BOUND'")
    int markDefault(String id);
}