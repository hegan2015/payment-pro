package com.qoder.payment.accounting.mapper;

import com.qoder.payment.accounting.dataobject.EntryDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EntryMapper {

    @Select("SELECT NEXT VALUE FOR seq_entry")
    long nextId();

    @Insert("INSERT INTO ledger_entry(id,seq,biz_no,account_id,dir,amount,balance_after,subject,ref_id,created_at) "
            + "VALUES(#{id},#{seq},#{bizNo},#{accountId},#{dir},#{amount},#{balanceAfter},#{subject},#{refId},#{createdAt})")
    int insert(EntryDO entry);

    @Select("SELECT * FROM ledger_entry WHERE account_id=#{accountId} ORDER BY seq DESC LIMIT #{limit}")
    List<EntryDO> selectByAccount(@Param("accountId") String accountId, @Param("limit") int limit);

    @Select("SELECT * FROM ledger_entry WHERE biz_no=#{bizNo} ORDER BY seq")
    List<EntryDO> selectByBizNo(String bizNo);

    @Select("SELECT COALESCE(SUM(CASE WHEN dir='D' THEN amount ELSE 0 END),0) FROM ledger_entry")
    long sumDebit();

    @Select("SELECT COALESCE(SUM(CASE WHEN dir='C' THEN amount ELSE 0 END),0) FROM ledger_entry")
    long sumCredit();

    @Select("SELECT COUNT(*) FROM ledger_entry")
    int count();
}