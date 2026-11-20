package com.qoder.payment.account.mapper;

import com.qoder.payment.account.dataobject.AccountDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AccountMapper {

    @Select("SELECT next_value_for('seq_account')")
    long nextId();

    @Insert("INSERT INTO account(id,member_id,member_name,type,status,created_at,account_type,level,freeze_type,currency) "
            + "VALUES(#{id},#{memberId},#{memberName},#{type},#{status},#{createdAt},"
            + "#{accountType},#{level},#{freezeType},#{currency})")
    int insert(AccountDO account);

    @Select("SELECT * FROM account WHERE id=#{id}")
    AccountDO selectById(String id);

    @Select("SELECT * FROM account ORDER BY created_at, id")
    List<AccountDO> selectAll();

    /** A-1：一会员多类型户（原 selectByMemberId 单行查询升级，getByMember 缺省取 BALANCE）。 */
    @Select("SELECT * FROM account WHERE member_id=#{memberId} ORDER BY created_at, id")
    List<AccountDO> selectListByMemberId(String memberId);

    /**
     * A-1 幂等键查询（口径不变）：缺省币种户 = CNY（存量 currency=NULL 亦按 CNY 读）。
     * A-4 后同会员同类型可另有外币户，本方法仍只命中 CNY 户 ⇒ createTyped 语义逐字节不变。
     */
    @Select("SELECT * FROM account WHERE member_id=#{memberId} AND account_type=#{accountType} "
            + "AND (currency='CNY' OR currency IS NULL) ORDER BY created_at, id LIMIT 1")
    AccountDO selectByMemberAndType(@Param("memberId") String memberId, @Param("accountType") String accountType);

    /** A-4 多币种幂等键查询：(memberId, accountType, currency) 唯一。 */
    @Select("SELECT * FROM account WHERE member_id=#{memberId} AND account_type=#{accountType} "
            + "AND (currency=#{currency} OR (currency IS NULL AND #{currency}='CNY')) ORDER BY created_at, id LIMIT 1")
    AccountDO selectByMemberTypeCurrency(@Param("memberId") String memberId, @Param("accountType") String accountType,
                                         @Param("currency") String currency);

    @Update("UPDATE account SET status=#{status} WHERE id=#{id}")
    int updateStatus(@Param("id") String id, @Param("status") String status);

    /** A-3 状态冻结细分（CAS：期望原状态，防并发覆盖）。 */
    @Update("UPDATE account SET status=#{status},freeze_type=#{freezeType},freeze_reason=#{freezeReason},"
            + "freeze_at=#{freezeAt} WHERE id=#{id} AND status=#{expectedStatus}")
    int updateStatusEx(AccountDO account);

    /** M-3 等级升级（只升不降由服务层校验）。 */
    @Update("UPDATE account SET level=#{level} WHERE id=#{id}")
    int updateLevel(@Param("id") String id, @Param("level") String level);

    // ---------- P2 G 泳道 additive（A-5 生命周期；既有方法与 SQL 一字未改） ----------

    /**
     * A-5 生命周期 CAS 迁移（ACTIVE→CLOSING→CLOSED，失败回 ACTIVE）。
     * CLOSED 时同步把 status 置 CLOSED ⇒ 账务域 checkAccounts 天然拒绝记账（不改 accounting 代码）。
     * <b>逻辑销户</b>：只 UPDATE 状态列，绝不 DELETE 账户行。
     */
    @Update("UPDATE account SET lifecycle_status=#{next},closed_at=#{closedAt},"
            + "status=CASE WHEN #{next}='CLOSED' THEN 'CLOSED' ELSE status END "
            + "WHERE id=#{id} AND lifecycle_status=#{expected}")
    int casLifecycle(@Param("id") String id, @Param("expected") String expected,
                     @Param("next") String next, @Param("closedAt") Long closedAt);

    /** A-5 等级变更 CAS（防并发覆盖；升降级方向与 KYC 由服务层校验）。 */
    @Update("UPDATE account SET level=#{target} WHERE id=#{id} AND level=#{expected}")
    int casLevel(@Param("id") String id, @Param("expected") String expected, @Param("target") String target);
}