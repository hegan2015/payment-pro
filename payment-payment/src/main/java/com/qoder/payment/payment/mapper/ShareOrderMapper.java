package com.qoder.payment.payment.mapper;

import com.qoder.payment.payment.dataobject.ShareOrderDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ShareOrderMapper {

    @Select("SELECT next_value_for('seq_share')")
    long nextId();

    @Insert("INSERT INTO share_order(id,payment_id,merchant_id,merchant_account_id,total_amount,share_type,status,"
            + "request_id,biz_no,execute_at,created_at) "
            + "VALUES(#{id},#{paymentId},#{merchantId},#{merchantAccountId},#{totalAmount},#{shareType},#{status},"
            + "#{requestId},#{bizNo},#{executeAt},#{createdAt})")
    int insert(ShareOrderDO order);

    @Select("SELECT * FROM share_order WHERE id=#{id}")
    ShareOrderDO selectById(String id);

    @Select("SELECT * FROM share_order WHERE request_id=#{requestId}")
    ShareOrderDO selectByRequestId(String requestId);

    @Select("SELECT * FROM share_order WHERE payment_id=#{paymentId} ORDER BY created_at ASC, id ASC")
    List<ShareOrderDO> selectByPaymentId(String paymentId);

    /** 可分额占用：PENDING/SHARED/BACKING 单合计（BACKED 已全额回退不占额）。 */
    @Select("SELECT COALESCE(SUM(total_amount),0) FROM share_order "
            + "WHERE payment_id=#{paymentId} AND status IN ('PENDING','SHARED','BACKING')")
    long sumActiveAllocated(String paymentId);

    @Select("SELECT COUNT(*) FROM share_order WHERE payment_id=#{paymentId}")
    int countByPaymentId(String paymentId);

    /** 到期/卡住的分账单（执行驱动扫描）。 */
    @Select("SELECT * FROM share_order WHERE status='PENDING' AND execute_at <= #{now} ORDER BY created_at ASC LIMIT #{limit}")
    List<ShareOrderDO> selectDuePending(@Param("now") long now, @Param("limit") int limit);

    @Select("SELECT * FROM share_order WHERE status='BACKING' ORDER BY created_at ASC LIMIT #{limit}")
    List<ShareOrderDO> selectBacking(@Param("limit") int limit);

    @Update("UPDATE share_order SET status='SHARED', shared_at=#{now}, fail_reason=NULL WHERE id=#{id} AND status='PENDING'")
    int markShared(@Param("id") String id, @Param("now") long now);

    @Update("UPDATE share_order SET status='BACKING', back_request_id=#{requestId} WHERE id=#{id} AND status='SHARED'")
    int markBacking(@Param("id") String id, @Param("requestId") String requestId);

    @Update("UPDATE share_order SET status='BACKED', backed_at=#{now} WHERE id=#{id} AND status='BACKING'")
    int markBacked(@Param("id") String id, @Param("now") long now);

    @Update("UPDATE share_order SET fail_reason=#{reason} WHERE id=#{id}")
    int updateFailReason(@Param("id") String id, @Param("reason") String reason);

    /** 执行失败退避：推迟下次驱动时间（不影响状态机）。 */
    @Update("UPDATE share_order SET execute_at=#{executeAt} WHERE id=#{id} AND status='PENDING'")
    int postpone(@Param("id") String id, @Param("executeAt") long executeAt);
}