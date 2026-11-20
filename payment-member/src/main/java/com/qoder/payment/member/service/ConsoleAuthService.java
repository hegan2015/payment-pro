package com.qoder.payment.member.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.auth.ConsoleUserDTO;
import com.qoder.payment.facade.auth.CreateUserRequest;
import com.qoder.payment.facade.auth.RoleDTO;
import com.qoder.payment.member.dataobject.ConsoleRoleDO;
import com.qoder.payment.member.dataobject.ConsoleUserDO;
import com.qoder.payment.member.mapper.ConsoleUserMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 控制台认证 + RBAC（S-2/G-1）：console_* 表落 pp_member 库，与业务会员分离。
 * <ul>
 *   <li>首次启动幂等种子：ADMIN/OPERATOR/AUDITOR/READONLY 四角色 + 权限矩阵 + admin 用户
 *       （口令来自环境变量 PP_ADMIN_PASSWORD，默认 admin123，BCrypt 落库，绝不落日志）。</li>
 *   <li>authenticate 失败统一 A0401「用户名或密码错误」（不泄露用户是否存在）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsoleAuthService {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_-]{3,32}");
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();

    /** 角色 → 权限矩阵（G-1，按端点域划分）。ADMIN 通配 "*"。 */
    private static final Map<String, List<String>> ROLE_PERMISSIONS = new LinkedHashMap<>();
    private static final Map<String, String> ROLE_NAMES = Map.of(
            "ADMIN", "管理员", "OPERATOR", "操作员", "AUDITOR", "审计员", "READONLY", "只读观察员");

    private static final List<String> READS = List.of(
            "member:read", "account:read", "accounting:read", "trade:read", "payment:read",
            "channel:read", "settle:read", "notify:read", "recon:read", "dispute:read", "stats:read",
            "risk:read", "merchant:read", "marketing:read");

    static {
        ROLE_PERMISSIONS.put("ADMIN", List.of("*"));
        List<String> operator = new ArrayList<>(READS);
        operator.addAll(List.of(
                "member:write", "account:write",
                "accounting:topup", "accounting:reverse", "accounting:suspense",
                "accounting:subject", "accounting:daycut", "accounting:reserve", "accounting:exchange",
                "trade:write", "payment:pay", "payment:refund", "payment:reconverge",
                "channel:receipt", "channel:write", "notify:send", "recon:run", "dispute:approve",
                "settle:write", "merchant:write", "merchant:review",
                "risk:write", "marketing:write"));
        ROLE_PERMISSIONS.put("OPERATOR", List.copyOf(operator));
        List<String> auditor = new ArrayList<>(READS);
        auditor.add("audit:read");
        ROLE_PERMISSIONS.put("AUDITOR", List.copyOf(auditor));
        ROLE_PERMISSIONS.put("READONLY", READS);
    }

    private final ConsoleUserMapper consoleUserMapper;

    /** 幂等种子：角色/权限/admin 用户只在缺失时插入，不覆盖既有数据（含已改口令）。 */
    @PostConstruct
    @Transactional
    public void seed() {
        for (Map.Entry<String, List<String>> entry : ROLE_PERMISSIONS.entrySet()) {
            String code = entry.getKey();
            ConsoleRoleDO role = consoleUserMapper.selectRoleByCode(code);
            if (role == null) {
                role = new ConsoleRoleDO();
                role.setId("CR" + consoleUserMapper.nextRoleId());
                role.setCode(code);
                role.setName(ROLE_NAMES.getOrDefault(code, code));
                consoleUserMapper.insertRole(role);
                log.info("控制台角色种子 role={}", code);
            }
            for (String permission : entry.getValue()) {
                if (consoleUserMapper.countRolePermission(role.getId(), permission) == 0) {
                    consoleUserMapper.insertRolePermission(role.getId(), permission);
                }
            }
        }
        if (consoleUserMapper.selectByUsername("admin") == null) {
            String password = System.getenv().getOrDefault("PP_ADMIN_PASSWORD", "admin123");
            ConsoleUserDO admin = new ConsoleUserDO();
            admin.setId("CU" + consoleUserMapper.nextUserId());
            admin.setUsername("admin");
            admin.setPasswordHash(BCRYPT.encode(password));
            admin.setStatus("ACTIVE");
            admin.setCreatedAt(System.currentTimeMillis());
            consoleUserMapper.insertUser(admin);
            ConsoleRoleDO adminRole = consoleUserMapper.selectRoleByCode("ADMIN");
            consoleUserMapper.insertUserRole(admin.getId(), adminRole.getId());
            log.info("控制台初始管理员 admin 已初始化（口令来自 PP_ADMIN_PASSWORD，BCrypt 存储）");
        }
    }

    public ConsoleUserDTO authenticate(String username, String password) {
        if (username == null || password == null || username.isBlank() || password.isEmpty()) {
            throw new BizException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        ConsoleUserDO user = consoleUserMapper.selectByUsername(username.trim());
        if (user == null || !"ACTIVE".equals(user.getStatus())
                || !BCRYPT.matches(password, user.getPasswordHash())) {
            throw new BizException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        return toDTO(user);
    }

    public ConsoleUserDTO getUser(String username) {
        ConsoleUserDO user = consoleUserMapper.selectByUsername(username);
        if (user == null) {
            throw new BizException(ResultCode.NOT_FOUND, "控制台用户不存在: " + username);
        }
        return toDTO(user);
    }

    @Transactional
    public ConsoleUserDTO createUser(CreateUserRequest request) {
        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        if (!USERNAME.matcher(username).matches()) {
            throw new BizException(ResultCode.PARAM_ERROR, "用户名须为 3-32 位字母/数字/_/-");
        }
        if (request.getPassword() == null || request.getPassword().length() < 6) {
            throw new BizException(ResultCode.PARAM_ERROR, "口令至少 6 位");
        }
        if (consoleUserMapper.selectByUsername(username) != null) {
            throw new BizException(ResultCode.CONFLICT, "用户名已存在: " + username);
        }
        List<String> roles = request.getRoles() == null || request.getRoles().isEmpty()
                ? List.of("READONLY") : request.getRoles();
        for (String role : roles) {
            if (consoleUserMapper.selectRoleByCode(role) == null) {
                throw new BizException(ResultCode.PARAM_ERROR, "角色不存在: " + role);
            }
        }
        ConsoleUserDO user = new ConsoleUserDO();
        user.setId("CU" + consoleUserMapper.nextUserId());
        user.setUsername(username);
        user.setPasswordHash(BCRYPT.encode(request.getPassword()));
        user.setStatus("ACTIVE");
        user.setCreatedAt(System.currentTimeMillis());
        consoleUserMapper.insertUser(user);
        for (String role : roles) {
            consoleUserMapper.insertUserRole(user.getId(), consoleUserMapper.selectRoleByCode(role).getId());
        }
        log.info("控制台用户创建 username={} roles={}", username, roles);
        return toDTO(user);
    }

    public List<ConsoleUserDTO> listUsers() {
        return consoleUserMapper.selectAllUsers().stream().map(this::toDTO).toList();
    }

    @Transactional
    public ConsoleUserDTO grantRole(String userId, String roleCode) {
        ConsoleUserDO user = requireUser(userId);
        ConsoleRoleDO role = consoleUserMapper.selectRoleByCode(roleCode);
        if (role == null) {
            throw new BizException(ResultCode.NOT_FOUND, "角色不存在: " + roleCode);
        }
        if (consoleUserMapper.countUserRole(user.getId(), role.getId()) == 0) {
            consoleUserMapper.insertUserRole(user.getId(), role.getId());
        }
        log.info("控制台授权 username={} role={}", user.getUsername(), roleCode);
        return toDTO(user);
    }

    @Transactional
    public ConsoleUserDTO revokeRole(String userId, String roleCode) {
        ConsoleUserDO user = requireUser(userId);
        ConsoleRoleDO role = consoleUserMapper.selectRoleByCode(roleCode);
        if (role == null) {
            throw new BizException(ResultCode.NOT_FOUND, "角色不存在: " + roleCode);
        }
        consoleUserMapper.deleteUserRole(user.getId(), role.getId());
        log.info("控制台撤权 username={} role={}", user.getUsername(), roleCode);
        return toDTO(user);
    }

    public ConsoleUserDTO updateUserStatus(String userId, String status) {
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw new BizException(ResultCode.PARAM_ERROR, "status 仅支持 ACTIVE 或 DISABLED");
        }
        ConsoleUserDO user = requireUser(userId);
        if ("admin".equals(user.getUsername()) && "DISABLED".equals(status)) {
            throw new BizException(ResultCode.CONFLICT, "初始管理员不可禁用");
        }
        consoleUserMapper.updateUserStatus(userId, status);
        return toDTO(requireUser(userId));
    }

    public List<RoleDTO> listRoles() {
        List<RoleDTO> result = new ArrayList<>();
        for (ConsoleRoleDO role : consoleUserMapper.selectAllRoles()) {
            RoleDTO dto = new RoleDTO();
            dto.setCode(role.getCode());
            dto.setName(role.getName());
            dto.setPermissions(consoleUserMapper.selectPermissionsOfRole(role.getId()));
            result.add(dto);
        }
        return result;
    }

    private ConsoleUserDO requireUser(String userId) {
        ConsoleUserDO user = consoleUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.NOT_FOUND, "控制台用户不存在: " + userId);
        }
        return user;
    }

    private ConsoleUserDTO toDTO(ConsoleUserDO user) {
        ConsoleUserDTO dto = new ConsoleUserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setStatus(user.getStatus());
        dto.setCreatedAt(user.getCreatedAt());
        dto.setRoles(consoleUserMapper.selectRolesOfUser(user.getId()).stream()
                .map(ConsoleRoleDO::getCode).toList());
        dto.setPermissions(consoleUserMapper.selectPermissionsOfUser(user.getId()));
        return dto;
    }
}