package com.qoder.payment.member.mapper;

import com.qoder.payment.member.dataobject.ConsoleRoleDO;
import com.qoder.payment.member.dataobject.ConsoleUserDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 控制台用户/角色/权限（S-2/G-1），遵循 member 域 seq 发号与注解 mapper 写法。 */
@Mapper
public interface ConsoleUserMapper {

    @Select("SELECT next_value_for('seq_console_user')")
    long nextUserId();

    @Select("SELECT next_value_for('seq_console_role')")
    long nextRoleId();

    @Insert("INSERT INTO console_user(id,username,password_hash,status,created_at) "
            + "VALUES(#{id},#{username},#{passwordHash},#{status},#{createdAt})")
    int insertUser(ConsoleUserDO user);

    @Select("SELECT * FROM console_user WHERE username=#{username}")
    ConsoleUserDO selectByUsername(String username);

    @Select("SELECT * FROM console_user WHERE id=#{id}")
    ConsoleUserDO selectById(String id);

    @Select("SELECT * FROM console_user ORDER BY created_at, id")
    List<ConsoleUserDO> selectAllUsers();

    @Update("UPDATE console_user SET status=#{status} WHERE id=#{id}")
    int updateUserStatus(@Param("id") String id, @Param("status") String status);

    @Insert("INSERT INTO console_role(id,code,name) VALUES(#{id},#{code},#{name})")
    int insertRole(ConsoleRoleDO role);

    @Select("SELECT * FROM console_role WHERE code=#{code}")
    ConsoleRoleDO selectRoleByCode(String code);

    @Select("SELECT * FROM console_role ORDER BY id")
    List<ConsoleRoleDO> selectAllRoles();

    @Insert("INSERT INTO console_user_role(user_id,role_id) VALUES(#{userId},#{roleId})")
    int insertUserRole(@Param("userId") String userId, @Param("roleId") String roleId);

    @Delete("DELETE FROM console_user_role WHERE user_id=#{userId} AND role_id=#{roleId}")
    int deleteUserRole(@Param("userId") String userId, @Param("roleId") String roleId);

    @Select("SELECT COUNT(*) FROM console_user_role WHERE user_id=#{userId} AND role_id=#{roleId}")
    int countUserRole(@Param("userId") String userId, @Param("roleId") String roleId);

    @Select("SELECT r.* FROM console_role r JOIN console_user_role ur ON r.id=ur.role_id "
            + "WHERE ur.user_id=#{userId} ORDER BY r.id")
    List<ConsoleRoleDO> selectRolesOfUser(String userId);

    @Insert("INSERT INTO role_permission(role_id,permission) VALUES(#{roleId},#{permission})")
    int insertRolePermission(@Param("roleId") String roleId, @Param("permission") String permission);

    @Select("SELECT COUNT(*) FROM role_permission WHERE role_id=#{roleId} AND permission=#{permission}")
    int countRolePermission(@Param("roleId") String roleId, @Param("permission") String permission);

    @Select("SELECT permission FROM role_permission WHERE role_id=#{roleId} ORDER BY permission")
    List<String> selectPermissionsOfRole(String roleId);

    @Select("SELECT DISTINCT rp.permission FROM role_permission rp "
            + "JOIN console_user_role ur ON rp.role_id=ur.role_id WHERE ur.user_id=#{userId} "
            + "ORDER BY rp.permission")
    List<String> selectPermissionsOfUser(String userId);
}