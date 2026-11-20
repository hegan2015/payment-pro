package com.qoder.payment.accounting.mapper;

import com.qoder.payment.accounting.dataobject.ReserveBankAdjustDO;
import com.qoder.payment.accounting.dataobject.ReserveCheckLogDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * S-7 备付金账实核对：核对留痕 + 模拟银行存管侧调整（只追加，绝不改账务数据）。
 *
 * <p>AC-5 additive：reserve_check_log 增 biz_date/channel_code/level/direction/dispute_no，
 * reserve_bank_adjust 增 channel_code。<b>P0 口径保留</b>：{@link #sumAdjust()} 仍为全表无条件求和，
 * {@link #insertLog} 之外的既有查询语义一字未改；新维度查询独立方法，null 等价 MOCK_BANK。
 */
@Mapper
public interface ReserveCheckMapper {

    @Select("SELECT next_value_for('seq_reserve')")
    long nextId();

    @Insert("INSERT INTO reserve_check_log(id,book_balance,actual_balance,diff,pass,checked_at,"
            + "biz_date,channel_code,level,direction,dispute_no) "
            + "VALUES(#{id},#{bookBalance},#{actualBalance},#{diff},#{pass},#{checkedAt},"
            + "#{bizDate},#{channelCode},#{level},#{direction},#{disputeNo})")
    int insertLog(ReserveCheckLogDO log);

    @Select("SELECT * FROM reserve_check_log ORDER BY checked_at DESC, id DESC LIMIT #{limit}")
    List<ReserveCheckLogDO> selectLogs(@Param("limit") int limit);

    @Insert("INSERT INTO reserve_bank_adjust(id,amount,reason,created_at,channel_code) "
            + "VALUES(#{id},#{amount},#{reason},#{createdAt},#{channelCode})")
    int insertAdjust(ReserveBankAdjustDO adjust);

    /** P0 口径：全部银行侧事件求和（不分渠道），顶层 SUM + COALESCE 单表安全（本表不分片）。 */
    @Select("SELECT COALESCE(SUM(amount),0) FROM reserve_bank_adjust")
    long sumAdjust();

    // ---------------- AC-5 新维度（只读 + 追加） ----------------

    /** 按渠道求和；channelCode 为 null/空/MOCK_BANK 时含历史 NULL 行（P0 存量口径不变）。 */
    @Select("<script>SELECT COALESCE(SUM(amount),0) FROM reserve_bank_adjust "
            + "<where><choose>"
            + "<when test='channelCode == null or channelCode == \"\" or channelCode == \"MOCK_BANK\"'>"
            + "AND (channel_code IS NULL OR channel_code = '' OR channel_code = 'MOCK_BANK')</when>"
            + "<otherwise>AND channel_code = #{channelCode}</otherwise>"
            + "</choose></where></script>")
    long sumAdjustByChannel(@Param("channelCode") String channelCode);

    @Select("<script>SELECT * FROM reserve_check_log "
            + "<where><if test='bizDate != null'> AND biz_date = #{bizDate}</if>"
            + "<if test='channelCode != null and channelCode != \"\"'> AND channel_code = #{channelCode}</if></where>"
            + " ORDER BY checked_at DESC, id DESC LIMIT #{limit}</script>")
    List<ReserveCheckLogDO> selectLogsBy(@Param("bizDate") Long bizDate,
                                         @Param("channelCode") String channelCode, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM reserve_check_log")
    int countLogs();

    @Select("SELECT COUNT(*) FROM reserve_bank_adjust")
    int countAdjust();
}