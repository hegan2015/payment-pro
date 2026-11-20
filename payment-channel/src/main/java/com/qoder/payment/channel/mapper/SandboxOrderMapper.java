package com.qoder.payment.channel.mapper;

import com.qoder.payment.channel.dataobject.SandboxOrderDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** CH-2b 沙箱银行侧订单账本（模拟外部渠道，与平台 channel_trade 分离）。 */
@Mapper
public interface SandboxOrderMapper {

    @Insert("INSERT INTO sandbox_order(channel_order_no,biz_no,type,amount,requested_amount,status,fault,biz_date,"
            + "due_at,created_at,finalized_at,receipt_sent,attempts) "
            + "VALUES(#{channelOrderNo},#{bizNo},#{type},#{amount},#{requestedAmount},#{status},#{fault},#{bizDate},"
            + "#{dueAt},#{createdAt},#{finalizedAt},#{receiptSent},#{attempts})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SandboxOrderDO order);

    @Select("SELECT * FROM sandbox_order WHERE biz_no=#{bizNo} AND type=#{type}")
    SandboxOrderDO selectByBizNo(@Param("bizNo") String bizNo, @Param("type") String type);

    /** 渠道侧查询（SPI query 只有 bizNo）：PAY/REFUND 业务号天然不冲突，取最新一单。 */
    @Select("SELECT * FROM sandbox_order WHERE biz_no=#{bizNo} ORDER BY id DESC LIMIT 1")
    SandboxOrderDO selectAnyByBizNo(String bizNo);

    @Select("SELECT * FROM sandbox_order WHERE channel_order_no=#{channelOrderNo}")
    SandboxOrderDO selectByOrderNo(String channelOrderNo);

    /** 到期未推回执的受理单（延迟/丢失注入由 fault 决定行为）。 */
    @Select("SELECT * FROM sandbox_order WHERE status='SUBMITTED' AND due_at IS NOT NULL AND due_at <= #{now} "
            + "ORDER BY due_at ASC LIMIT #{limit}")
    List<SandboxOrderDO> selectDue(@Param("now") long now, @Param("limit") int limit);

    /** 已终态但回执未送达（推送失败重试 / 回执丢失注入恒留此态）。 */
    @Select("SELECT * FROM sandbox_order WHERE receipt_sent=0 AND status<>'SUBMITTED' AND due_at IS NOT NULL "
            + "AND due_at <= #{now} ORDER BY due_at ASC LIMIT #{limit}")
    List<SandboxOrderDO> selectPendingReceipt(@Param("now") long now, @Param("limit") int limit);

    @Select("SELECT * FROM sandbox_order WHERE biz_date=#{bizDate} ORDER BY id ASC")
    List<SandboxOrderDO> selectByBizDate(String bizDate);

    @Select("SELECT * FROM sandbox_order ORDER BY id DESC LIMIT #{limit}")
    List<SandboxOrderDO> selectRecent(@Param("limit") int limit);

    @Update("UPDATE sandbox_order SET status=#{to}, finalized_at=#{now} WHERE id=#{id} AND status=#{from}")
    int casStatus(@Param("id") long id, @Param("from") String from, @Param("to") String to, @Param("now") long now);

    @Update("UPDATE sandbox_order SET receipt_sent=1 WHERE id=#{id}")
    int markReceiptSent(long id);

    /** 回执重试耗尽并已建差错单：停止推送（2），留人工核对，绝不改写平台状态。 */
    @Update("UPDATE sandbox_order SET receipt_sent=2 WHERE id=#{id}")
    int markDisputed(long id);

    @Update("UPDATE sandbox_order SET attempts=attempts+1 WHERE id=#{id}")
    int incrAttempts(long id);

    @Update("UPDATE sandbox_order SET amount=#{amount}, fault=#{fault} WHERE id=#{id}")
    int updateAmount(@Param("id") long id, @Param("amount") long amount, @Param("fault") String fault);
}