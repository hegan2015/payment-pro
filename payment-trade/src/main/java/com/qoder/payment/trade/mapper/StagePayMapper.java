package com.qoder.payment.trade.mapper;

import com.qoder.payment.trade.dataobject.OrderStagePlanDO;
import com.qoder.payment.trade.dataobject.StagePayLogDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** T-5 分阶段计划 + 阶段支付留痕（单库不分片；UNIQUE 键兜底幂等）。 */
@Mapper
public interface StagePayMapper {

    @Select("SELECT next_value_for('seq_stage_log')")
    long nextId();

    @Insert("INSERT INTO order_stage_plan(order_id,stage_seq,amount) VALUES(#{orderId},#{stageSeq},#{amount})")
    int insertPlan(OrderStagePlanDO plan);

    @Select("SELECT * FROM order_stage_plan WHERE order_id=#{orderId} ORDER BY stage_seq")
    List<OrderStagePlanDO> selectPlan(String orderId);

    @Insert("INSERT INTO stage_pay_log(id,order_id,stage_seq,amount,payment_id,request_id,status,created_at,updated_at) "
            + "VALUES(#{id},#{orderId},#{stageSeq},#{amount},#{paymentId},#{requestId},#{status},#{createdAt},#{updatedAt})")
    int insertLog(StagePayLogDO log);

    @Select("SELECT * FROM stage_pay_log WHERE order_id=#{orderId} ORDER BY stage_seq")
    List<StagePayLogDO> selectLogs(String orderId);

    @Select("SELECT * FROM stage_pay_log WHERE payment_id=#{paymentId}")
    StagePayLogDO selectLogByPayment(String paymentId);

    @Select("SELECT * FROM stage_pay_log WHERE request_id=#{requestId}")
    StagePayLogDO selectLogByRequest(String requestId);

    /** 期状态推进 CAS（PROCESSING→SUCCESS/FAILED/CANCELLED，只翻一次）。 */
    @Update("UPDATE stage_pay_log SET status=#{toStatus}, updated_at=#{now} WHERE payment_id=#{paymentId} AND status='PROCESSING'")
    int finishLog(@Param("paymentId") String paymentId, @Param("toStatus") String toStatus, @Param("now") long now);

    /** 撤销释放专用：已确认期 SUCCESS→CANCELLED（仅当日撤销回退路径调用，CAS 只翻一次）。 */
    @Update("UPDATE stage_pay_log SET status='CANCELLED', updated_at=#{now} WHERE payment_id=#{paymentId} AND status='SUCCESS'")
    int cancelConfirmedLog(@Param("paymentId") String paymentId, @Param("now") long now);
}