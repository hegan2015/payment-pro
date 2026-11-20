package com.qoder.payment.marketing.mapper;

import com.qoder.payment.facade.marketing.PointsAccountDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * X-3b 积分账户（虚拟权益独立账本，单位=积分）。
 *
 * <p>所有变动都是<b>原子条件 UPDATE</b>（available=balance−frozen 充足才扣），
 * 并发/重放不会扣成负数；version 每次 +1 供观测。积分不是金额，不参与复式记账与 Σbalance=0。
 */
@Mapper
public interface PointsAccountMapper {

    @Insert("INSERT INTO points_account(member_id,balance,frozen,expired,version,created_at,updated_at) "
            + "VALUES(#{memberId},#{balance},#{frozen},#{expired},#{version},#{createdAt},#{updatedAt})")
    int insert(PointsAccountDTO account);

    @Select("SELECT * FROM points_account WHERE member_id=#{memberId}")
    PointsAccountDTO selectById(String memberId);

    @Select("SELECT * FROM points_account WHERE member_id=#{memberId} FOR UPDATE")
    PointsAccountDTO selectForUpdate(String memberId);

    @Update("UPDATE points_account SET balance=balance+#{points}, version=version+1, updated_at=#{now} "
            + "WHERE member_id=#{memberId}")
    int earn(@Param("memberId") String memberId, @Param("points") long points, @Param("now") long now);

    /** 消耗可用积分：balance−frozen ≥ points 才扣（原子）。 */
    @Update("UPDATE points_account SET balance=balance-#{points}, version=version+1, updated_at=#{now} "
            + "WHERE member_id=#{memberId} AND balance-frozen>=#{points}")
    int use(@Param("memberId") String memberId, @Param("points") long points, @Param("now") long now);

    /** 冻结转消耗：同时扣 balance 与 frozen（原子）。 */
    @Update("UPDATE points_account SET balance=balance-#{points}, frozen=frozen-#{points}, "
            + "version=version+1, updated_at=#{now} "
            + "WHERE member_id=#{memberId} AND frozen>=#{points} AND balance>=#{points}")
    int settleFrozen(@Param("memberId") String memberId, @Param("points") long points, @Param("now") long now);

    @Update("UPDATE points_account SET frozen=frozen+#{points}, version=version+1, updated_at=#{now} "
            + "WHERE member_id=#{memberId} AND balance-frozen>=#{points}")
    int freeze(@Param("memberId") String memberId, @Param("points") long points, @Param("now") long now);

    @Update("UPDATE points_account SET frozen=frozen-#{points}, version=version+1, updated_at=#{now} "
            + "WHERE member_id=#{memberId} AND frozen>=#{points}")
    int unfreeze(@Param("memberId") String memberId, @Param("points") long points, @Param("now") long now);

    /** 过期：只扣未冻结部分（冻结积分不静默蒸发，需先解冻）。 */
    @Update("UPDATE points_account SET balance=balance-#{points}, expired=expired+#{points}, "
            + "version=version+1, updated_at=#{now} "
            + "WHERE member_id=#{memberId} AND balance-frozen>=#{points}")
    int expire(@Param("memberId") String memberId, @Param("points") long points, @Param("now") long now);

    @Select("SELECT COUNT(*) FROM points_account")
    int count();
}