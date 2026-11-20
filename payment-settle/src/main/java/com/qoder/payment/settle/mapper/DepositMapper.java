package com.qoder.payment.settle.mapper;

import com.qoder.payment.facade.settle.DepositRecordDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * ST-6 保证金单据（<b>单库不分片</b>：request_id 是全局幂等键，且缴存单含 CAS 状态机 UPDATE，
 * 分片后唯一键无法跨片保证、影子写只 INSERT 会破坏回滚一致性）。
 */
@Mapper
public interface DepositMapper {

    @Insert("INSERT INTO deposit_record(id,merchant_id,account_id,type,ref_id,amount,released_amount,"
            + "deducted_amount,status,biz_no,request_id,operator,reason,target_account,advance_id,created_at) "
            + "VALUES(#{id},#{merchantId},#{accountId},#{type},#{refId},#{amount},#{releasedAmount},"
            + "#{deductedAmount},#{status},#{bizNo},#{requestId},#{operator},#{reason},#{targetAccount},"
            + "#{advanceId},#{createdAt})")
    int insert(DepositRecordDTO record);

    @Select("SELECT * FROM deposit_record WHERE id=#{id}")
    DepositRecordDTO selectById(String id);

    @Select("SELECT * FROM deposit_record WHERE request_id=#{requestId}")
    DepositRecordDTO selectByRequestId(String requestId);

    @Select("SELECT * FROM deposit_record WHERE merchant_id=#{merchantId} ORDER BY created_at, id")
    List<DepositRecordDTO> selectByMerchant(String merchantId);

    @Select("SELECT * FROM deposit_record ORDER BY created_at, id")
    List<DepositRecordDTO> selectAll();

    /** FIFO 可用缴存单（剩余额度 > 0），释放/扣划未指定 depositId 时取最早一笔。 */
    @Select("SELECT * FROM deposit_record WHERE merchant_id=#{merchantId} AND type='DEPOSIT' "
            + "AND amount > released_amount + deducted_amount ORDER BY created_at, id")
    List<DepositRecordDTO> selectAvailable(String merchantId);

    @Select("SELECT SUM(amount) FROM deposit_record WHERE merchant_id=#{merchantId} AND type=#{type}")
    Long sumAmount(@Param("merchantId") String merchantId, @Param("type") String type);

    @Select("SELECT SUM(amount) FROM deposit_record WHERE type=#{type}")
    Long sumAmountGlobal(String type);

    /** 缴存单消耗 CAS（乐观并发：期望原 released/deducted，超额释放/扣划一律拒绝）。 */
    @Update("UPDATE deposit_record SET released_amount=#{newReleased},deducted_amount=#{newDeducted},"
            + "status=#{status} WHERE id=#{id} AND released_amount=#{expReleased} "
            + "AND deducted_amount=#{expDeducted}")
    int casConsume(@Param("id") String id, @Param("expReleased") long expReleased,
                   @Param("expDeducted") long expDeducted, @Param("newReleased") long newReleased,
                   @Param("newDeducted") long newDeducted, @Param("status") String status);
}