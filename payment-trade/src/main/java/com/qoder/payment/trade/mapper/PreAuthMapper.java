package com.qoder.payment.trade.mapper;

import com.qoder.payment.trade.dataobject.PreAuthCompleteDO;
import com.qoder.payment.trade.dataobject.PreAuthRecordDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * T-2 预授权单与完成留痕（单库不分片：order_id / request_id 是全局幂等键）。
 * 冻结额真值在 account 域 A-3 冻结单 + 账务 frozen 列，本表只记产品状态与累计完成额。
 */
@Mapper
public interface PreAuthMapper {

    @Select("SELECT next_value_for('seq_preauth')")
    long nextRecordId();

    @Select("SELECT next_value_for('seq_preauth_complete')")
    long nextCompleteId();

    @Insert("INSERT INTO preauth_record(id,order_id,account_id,auth_amount,completed_amount,status,freeze_id,request_id,created_at,updated_at) "
            + "VALUES(#{id},#{orderId},#{accountId},#{authAmount},#{completedAmount},#{status},#{freezeId},#{requestId},#{createdAt},#{updatedAt})")
    int insertRecord(PreAuthRecordDO record);

    @Select("SELECT * FROM preauth_record WHERE order_id=#{orderId}")
    PreAuthRecordDO selectByOrderId(@Param("orderId") String orderId);

    @Select("<script>SELECT * FROM preauth_record WHERE order_id IN "
            + "<foreach item='id' collection='orderIds' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<PreAuthRecordDO> selectByOrderIds(@Param("orderIds") List<String> orderIds);

    /**
     * 完成扣款 CAS：期望累计额一致 + 剩余额度足够才推进（并发完成/超扣一律 0 行拒绝）。
     * 冻结/解冻/过账远端步骤在本 CAS 之后执行，bizNo 由 (orderId,expectedCompleted) 确定性派生 ⇒ 重试收敛。
     */
    @Update("UPDATE preauth_record SET completed_amount=completed_amount+#{amount}, status=#{status}, updated_at=#{now} "
            + "WHERE order_id=#{orderId} AND completed_amount=#{expected} AND auth_amount-completed_amount>=#{amount} "
            + "AND status IN ('AUTHORIZED','PARTIAL_COMPLETED')")
    int advanceComplete(@Param("orderId") String orderId, @Param("expected") long expected,
                        @Param("amount") long amount, @Param("status") String status, @Param("now") long now);

    @Update("UPDATE preauth_record SET freeze_id=#{freezeId,jdbcType=VARCHAR}, updated_at=#{now} WHERE order_id=#{orderId}")
    int updateFreeze(@Param("orderId") String orderId, @Param("freezeId") String freezeId, @Param("now") long now);

    /** 撤销 CAS：仅 AUTHORIZED/PARTIAL_COMPLETED 可撤销（已完成/已撤销 0 行）。 */
    @Update("UPDATE preauth_record SET status='CANCELLED', freeze_id=NULL, updated_at=#{now} "
            + "WHERE order_id=#{orderId} AND status IN ('AUTHORIZED','PARTIAL_COMPLETED')")
    int cancelRecord(@Param("orderId") String orderId, @Param("now") long now);

    @Insert("INSERT INTO preauth_complete_log(id,order_id,amount,biz_no,request_id,released_freeze_id,refreeze_id,status,created_at) "
            + "VALUES(#{id},#{orderId},#{amount},#{bizNo},#{requestId},#{releasedFreezeId},#{refreezeId},#{status},#{createdAt})")
    int insertComplete(PreAuthCompleteDO log);

    @Select("SELECT * FROM preauth_complete_log WHERE request_id=#{requestId}")
    PreAuthCompleteDO selectCompleteByRequestId(@Param("requestId") String requestId);

    @Select("SELECT * FROM preauth_complete_log WHERE order_id=#{orderId} ORDER BY created_at ASC, id ASC")
    List<PreAuthCompleteDO> selectCompletes(@Param("orderId") String orderId);

    @Update("UPDATE preauth_complete_log SET status=#{status}, refreeze_id=#{refreezeId,jdbcType=VARCHAR} WHERE id=#{id}")
    int finishComplete(@Param("id") String id, @Param("status") String status, @Param("refreezeId") String refreezeId);
}