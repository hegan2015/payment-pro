package com.qoder.payment.member.mapper;

import com.qoder.payment.member.dataobject.AuditLogDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 操作审计（G-1）：只追加，不提供 UPDATE/DELETE（不可篡改）。 */
@Mapper
public interface AuditLogMapper {

    @Select("SELECT next_value_for('seq_audit')")
    long nextId();

    @Insert("INSERT INTO audit_log(id,username,action,method,path,biz_no,detail,result,ip,trace_id,created_at) "
            + "VALUES(#{id},#{username},#{action},#{method},#{path},#{bizNo},#{detail},#{result},#{ip},#{traceId},#{createdAt})")
    int insert(AuditLogDO log);

    @Select("<script>SELECT * FROM audit_log "
            + "<where>"
            + "<if test='username != null and username != \"\"'>AND username=#{username}</if>"
            + "<if test='action != null and action != \"\"'>AND action=#{action}</if>"
            + "<if test='path != null and path != \"\"'>AND path LIKE CONCAT(#{path},'%')</if>"
            + "</where>"
            + "ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<AuditLogDO> selectPage(@Param("username") String username, @Param("action") String action,
                                @Param("path") String path, @Param("size") int size, @Param("offset") int offset);

    @Select("<script>SELECT COUNT(*) FROM audit_log "
            + "<where>"
            + "<if test='username != null and username != \"\"'>AND username=#{username}</if>"
            + "<if test='action != null and action != \"\"'>AND action=#{action}</if>"
            + "<if test='path != null and path != \"\"'>AND path LIKE CONCAT(#{path},'%')</if>"
            + "</where></script>")
    long countPage(@Param("username") String username, @Param("action") String action, @Param("path") String path);
}