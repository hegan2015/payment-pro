package com.qoder.payment.channel.mapper;

import com.qoder.payment.channel.dataobject.ChannelTradeDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ChannelTradeMapper {

    @Insert("INSERT INTO channel_trade(biz_no,type,orig_biz_no,card_no,amount,status,reason,created_at,channel_order_no,submitted_at,channel_code) "
            + "VALUES(#{bizNo},#{type},#{origBizNo},#{cardNo},#{amount},#{status},#{reason},#{createdAt},#{channelOrderNo},#{submittedAt},#{channelCode})")
    int insert(ChannelTradeDO trade);

    @Select("SELECT * FROM channel_trade WHERE biz_no=#{bizNo}")
    ChannelTradeDO selectByBizNo(String bizNo);

    @Select("SELECT * FROM channel_trade ORDER BY created_at DESC, biz_no DESC")
    List<ChannelTradeDO> selectAll();

    @Update("UPDATE channel_trade SET status=#{to} WHERE biz_no=#{bizNo} AND status=#{from}")
    int casStatus(@Param("bizNo") String bizNo, @Param("from") String from, @Param("to") String to);

    /** 终态 CAS（回执驱动）：只允许 from→to 翻一次，附带原因与回执时间，重复回执影响 0 行。 */
    @Update("UPDATE channel_trade SET status=#{to}, reason=#{reason}, receipt_at=#{receiptAt} "
            + "WHERE biz_no=#{bizNo} AND status=#{from}")
    int casTerminal(@Param("bizNo") String bizNo, @Param("from") String from, @Param("to") String to,
                    @Param("reason") String reason, @Param("receiptAt") Long receiptAt);

    /** 补录渠道方订单号：仅在尚未记录时写入，不覆盖既有值。 */
    @Update("UPDATE channel_trade SET channel_order_no=#{channelOrderNo} "
            + "WHERE biz_no=#{bizNo} AND channel_order_no IS NULL")
    int updateChannelOrderNo(@Param("bizNo") String bizNo, @Param("channelOrderNo") String channelOrderNo);

    /** 异步受理超时单（模拟银行延迟落地终态用）：status=SUBMITTED 且受理时间早于 before。
     *  CH-2b：只处理模拟银行渠道（含历史无渠道码行），沙箱渠道单由 SandboxQueryJob 查询补单收敛，
     *  避免模拟银行 job 越过沙箱侧真实终态抢先落地（不臆断渠道结果）。 */
    @Select("SELECT * FROM channel_trade WHERE status='SUBMITTED' AND submitted_at IS NOT NULL AND submitted_at < #{before} "
            + "AND (channel_code IS NULL OR channel_code='MOCK_BANK' OR channel_code='MOCK_BANK_B') "
            + "ORDER BY submitted_at ASC LIMIT #{limit}")
    List<ChannelTradeDO> selectSubmittedBefore(@Param("before") long before, @Param("limit") int limit);

    /** CH-4 账单生成/比对：某渠道在时间窗内的终态流水（created_at 毫秒区间）。 */
    @Select("SELECT * FROM channel_trade WHERE created_at >= #{from} AND created_at <= #{to} "
            + "AND status IN ('CAPTURED','REFUNDED','FAILED') ORDER BY created_at ASC, biz_no ASC")
    List<ChannelTradeDO> selectTerminalInRange(@Param("from") long from, @Param("to") long to);

    /** CH-2b 补单：某渠道受理中（SUBMITTED）且受理时间早于 before 的单（含历史无渠道码行）。 */
    @Select("SELECT * FROM channel_trade WHERE status='SUBMITTED' AND submitted_at IS NOT NULL AND submitted_at < #{before} "
            + "AND channel_code=#{channelCode} ORDER BY submitted_at ASC LIMIT #{limit}")
    List<ChannelTradeDO> selectSubmittedByChannel(@Param("channelCode") String channelCode,
                                                 @Param("before") long before, @Param("limit") int limit);
}