package com.qoder.payment.member.openplatform;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** G-4a 沙箱账本（账户/资金单/交易单）：全部 openapi_sandbox_* 新表，与生产资金表零交集。 */
@Mapper
public interface SandboxMapper {

    @Select("SELECT next_value_for('seq_openapi_sbx_account')")
    long nextAccountId();

    @Select("SELECT next_value_for('seq_openapi_sbx_txn')")
    long nextTxnId();

    @Select("SELECT next_value_for('seq_openapi_sbx_order')")
    long nextOrderId();

    // ---------- 账户 ----------

    @Insert("INSERT INTO openapi_sandbox_account(account_id,app_id,member_id,name,type,balance,created_at) "
            + "VALUES(#{accountId},#{appId},#{memberId},#{name},#{type},#{balance},#{createdAt})")
    int insertAccount(SandboxAccountDO account);

    @Select("SELECT * FROM openapi_sandbox_account WHERE account_id=#{accountId}")
    SandboxAccountDO selectAccount(String accountId);

    @Select("SELECT * FROM openapi_sandbox_account WHERE app_id=#{appId} AND name=#{name} AND type=#{type}")
    SandboxAccountDO selectAccountByName(@Param("appId") String appId, @Param("name") String name,
                                         @Param("type") String type);

    @Select("SELECT * FROM openapi_sandbox_account WHERE app_id=#{appId} ORDER BY created_at, account_id")
    List<SandboxAccountDO> selectAccounts(String appId);

    /** 余额变动（透支保护：变动后 < 0 则不更新，影响 0 行 → 余额不足）。 */
    @Update("UPDATE openapi_sandbox_account SET balance=balance+#{delta} "
            + "WHERE account_id=#{accountId} AND app_id=#{appId} AND balance+#{delta} >= 0")
    int moveBalance(@Param("accountId") String accountId, @Param("appId") String appId, @Param("delta") long delta);

    // ---------- 资金单/支付单 ----------

    @Insert("INSERT INTO openapi_sandbox_txn(id,app_id,biz_type,method,payer_account_id,to_account_id,order_id,"
            + "amount,fee,status,request_id,created_at) "
            + "VALUES(#{id},#{appId},#{bizType},#{method},#{payerAccountId},#{toAccountId},#{orderId},"
            + "#{amount},#{fee},#{status},#{requestId},#{createdAt})")
    int insertTxn(SandboxTxnDO txn);

    @Select("SELECT * FROM openapi_sandbox_txn WHERE app_id=#{appId} AND request_id=#{requestId}")
    SandboxTxnDO selectTxnByRequest(@Param("appId") String appId, @Param("requestId") String requestId);

    @Select("SELECT * FROM openapi_sandbox_txn WHERE id=#{id} AND app_id=#{appId}")
    SandboxTxnDO selectTxn(@Param("id") String id, @Param("appId") String appId);

    /** UI-B additive：沙箱资金单列表（只读，倒序，限量）。 */
    @Select("SELECT * FROM openapi_sandbox_txn WHERE app_id=#{appId} ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<SandboxTxnDO> selectTxns(@Param("appId") String appId, @Param("limit") int limit);

    // ---------- 交易单 ----------

    @Insert("INSERT INTO openapi_sandbox_order(id,app_id,merchant_id,out_trade_no,subject,amount,status,payment_id,"
            + "created_at,paid_at) VALUES(#{id},#{appId},#{merchantId},#{outTradeNo},#{subject},#{amount},#{status},"
            + "#{paymentId},#{createdAt},#{paidAt})")
    int insertOrder(SandboxOrderDO order);

    @Select("SELECT * FROM openapi_sandbox_order WHERE id=#{id} AND app_id=#{appId}")
    SandboxOrderDO selectOrder(@Param("id") String id, @Param("appId") String appId);

    @Select("SELECT * FROM openapi_sandbox_order WHERE app_id=#{appId} AND out_trade_no=#{outTradeNo}")
    SandboxOrderDO selectOrderByOutTradeNo(@Param("appId") String appId, @Param("outTradeNo") String outTradeNo);

    /** UI-B additive：沙箱交易单列表（只读，倒序，限量）。 */
    @Select("SELECT * FROM openapi_sandbox_order WHERE app_id=#{appId} ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<SandboxOrderDO> selectOrders(@Param("appId") String appId, @Param("limit") int limit);

    @Update("UPDATE openapi_sandbox_order SET status=#{status},payment_id=#{paymentId},paid_at=#{paidAt} "
            + "WHERE id=#{id} AND app_id=#{appId}")
    int markPaid(@Param("id") String id, @Param("appId") String appId, @Param("status") String status,
                 @Param("paymentId") String paymentId, @Param("paidAt") long paidAt);
}