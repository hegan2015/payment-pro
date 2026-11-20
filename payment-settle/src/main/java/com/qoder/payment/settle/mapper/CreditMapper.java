package com.qoder.payment.settle.mapper;

import com.qoder.payment.facade.settle.CreditAccountDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** ST-6 授信额度（单库不分片；used 原子扣减，并发不超授信）。 */
@Mapper
public interface CreditMapper {

    @Insert("INSERT INTO credit_account(merchant_id,credit_limit,used,status,version,operator,created_at,updated_at) "
            + "VALUES(#{merchantId},#{creditLimit},#{used},#{status},#{version},#{operator},#{createdAt},#{updatedAt})")
    int insert(CreditAccountDTO credit);

    @Select("SELECT * FROM credit_account WHERE merchant_id=#{merchantId}")
    CreditAccountDTO selectByMerchant(String merchantId);

    /** 额度维护：新额度不得低于已占用（used），否则拒绝（防授信收缩造成超额）。 */
    @Update("UPDATE credit_account SET credit_limit=#{creditLimit},status=#{status},version=version+1,"
            + "operator=#{operator},updated_at=#{updatedAt} WHERE merchant_id=#{merchantId} AND used<=#{creditLimit}")
    int updateLimit(@Param("merchantId") String merchantId, @Param("creditLimit") long creditLimit,
                    @Param("status") String status, @Param("operator") String operator,
                    @Param("updatedAt") long updatedAt);

    /** 原子占用（并发不超授信：WHERE 内做额度判定，DB 行锁串行化）。 */
    @Update("UPDATE credit_account SET used=used+#{amount},version=version+1,updated_at=#{updatedAt} "
            + "WHERE merchant_id=#{merchantId} AND status='ACTIVE' AND used+#{amount}<=credit_limit")
    int casReserve(@Param("merchantId") String merchantId, @Param("amount") long amount,
                   @Param("updatedAt") long updatedAt);

    /** 原子释放（追偿/保证金偿还垫资后回收额度）。 */
    @Update("UPDATE credit_account SET used=used-#{amount},version=version+1,updated_at=#{updatedAt} "
            + "WHERE merchant_id=#{merchantId} AND used>=#{amount}")
    int casRelease(@Param("merchantId") String merchantId, @Param("amount") long amount,
                   @Param("updatedAt") long updatedAt);
}