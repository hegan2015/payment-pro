package com.qoder.payment.settle.mapper;

import com.qoder.payment.facade.settle.WithdrawOrderDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** ST-4 提现单（单库不分片：request_id 是全局幂等键，分片后 DB 唯一键无法跨片保证）。 */
@Mapper
public interface WithdrawOrderMapper {

    @Insert("INSERT INTO withdraw_order(id,account_id,merchant_id,amount,fee,bank_card_no,channel_code,"
            + "channel_order_no,status,request_id,biz_no,reconcile_count,fail_reason,created_at,updated_at) "
            + "VALUES(#{id},#{accountId},#{merchantId},#{amount},#{fee},#{bankCardNo},#{channelCode},"
            + "#{channelOrderNo},#{status},#{requestId},#{bizNo},#{reconcileCount},#{failReason},"
            + "#{createdAt},#{updatedAt})")
    int insert(WithdrawOrderDTO order);

    @Select("SELECT * FROM withdraw_order WHERE id=#{id}")
    WithdrawOrderDTO selectById(String id);

    @Select("SELECT * FROM withdraw_order WHERE request_id=#{requestId}")
    WithdrawOrderDTO selectByRequestId(String requestId);

    @Select("SELECT * FROM withdraw_order WHERE status=#{status} ORDER BY created_at, id")
    List<WithdrawOrderDTO> selectByStatus(String status);

    @Select("SELECT * FROM withdraw_order ORDER BY created_at DESC, id DESC")
    List<WithdrawOrderDTO> selectAll();

    /** 终态 CAS：只有 PROCESSING 能翻终态，重复回执/补单被吸收（不重复动账）。 */
    @Update("UPDATE withdraw_order SET status=#{status}, channel_order_no=#{channelOrderNo}, "
            + "fail_reason=#{failReason}, updated_at=#{updatedAt} WHERE id=#{id} AND status='PROCESSING'")
    int casTerminal(@Param("id") String id, @Param("status") String status,
                    @Param("channelOrderNo") String channelOrderNo, @Param("failReason") String failReason,
                    @Param("updatedAt") long updatedAt);

    @Update("UPDATE withdraw_order SET reconcile_count=reconcile_count+1, updated_at=#{updatedAt} WHERE id=#{id}")
    int bumpReconcile(@Param("id") String id, @Param("updatedAt") long updatedAt);

    /** ST-6 additive：A-5 销户护栏用——某账户在途提现单（只读，不改既有 SQL）。 */
    @Select("SELECT * FROM withdraw_order WHERE account_id=#{accountId} AND status=#{status} ORDER BY created_at, id")
    List<WithdrawOrderDTO> selectByAccountAndStatus(@Param("accountId") String accountId,
                                                    @Param("status") String status);
}