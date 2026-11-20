package com.qoder.payment.channel.mapper;

import com.qoder.payment.channel.dataobject.SignAuditDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** CH-6/X-4 授权审计（只追加，绝不改写/删除）。 */
@Mapper
public interface SignAuditMapper {

    @Insert("INSERT INTO sign_audit(sign_no,deduct_no,action,operator,request_id,amount,result,reason,created_at) "
            + "VALUES(#{signNo},#{deductNo},#{action},#{operator},#{requestId},#{amount},#{result},#{reason},#{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SignAuditDO audit);

    @Select("<script>SELECT * FROM sign_audit WHERE 1=1 "
            + "<if test='signNo != null'> AND sign_no=#{signNo}</if>"
            + " ORDER BY id DESC LIMIT #{limit}</script>")
    List<SignAuditDO> selectList(@Param("signNo") String signNo, @Param("limit") int limit);
}