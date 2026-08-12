package com.gewu.application.auth;

import com.gewu.application.auth.dto.RegisterCommand;
import com.gewu.application.workspace.WorkspaceService;
import com.gewu.common.crypto.PasswordPolicy;
import com.gewu.common.jwt.JwtUtil;
import com.gewu.common.result.BusinessException;
import com.gewu.domain.user.UserAccount;
import com.gewu.infrastructure.cache.CacheService;
import com.gewu.infrastructure.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AuthService 注册密码策略测试。
 */
@ExtendWith(MockitoExtension.class)
class AuthServicePasswordPolicyTest {

    @Mock
    private UserAccountMapper userMapper;

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private PermissionMapper permissionMapper;

    @Mock
    private UserRoleMapper userRoleMapper;

    @Mock
    private RolePermissionMapper rolePermissionMapper;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private CacheService cacheService;

    @Mock
    private WorkspaceService workspaceService;

    private PasswordPolicy passwordPolicy;
    private AuthService authService;

    @BeforeEach
    void setUp() throws Exception {
        // 创建 PasswordPolicy 实例并设置默认配置
        passwordPolicy = new PasswordPolicy();
        Field minLengthField = PasswordPolicy.class.getDeclaredField("minLength");
        minLengthField.setAccessible(true);
        minLengthField.set(passwordPolicy, 8);
        
        Field requireUppercaseField = PasswordPolicy.class.getDeclaredField("requireUppercase");
        requireUppercaseField.setAccessible(true);
        requireUppercaseField.set(passwordPolicy, true);
        
        Field requireLowercaseField = PasswordPolicy.class.getDeclaredField("requireLowercase");
        requireLowercaseField.setAccessible(true);
        requireLowercaseField.set(passwordPolicy, true);
        
        Field requireDigitField = PasswordPolicy.class.getDeclaredField("requireDigit");
        requireDigitField.setAccessible(true);
        requireDigitField.set(passwordPolicy, true);
        
        Field requireSpecialField = PasswordPolicy.class.getDeclaredField("requireSpecial");
        requireSpecialField.setAccessible(true);
        requireSpecialField.set(passwordPolicy, true);

        // 创建 AuthService 实例
        authService = new AuthService(
                userMapper,
                roleMapper,
                permissionMapper,
                userRoleMapper,
                rolePermissionMapper,
                jwtUtil,
                passwordPolicy,
                cacheService,
                workspaceService
        );
    }

    @Test
    @DisplayName("CR-001: 弱密码注册被拒绝 - 密码过短")
    void register_rejectsWeakPassword_tooShort() {
        RegisterCommand command = new RegisterCommand();
        command.setUsername("testuser");
        command.setEmail("test@example.com");
        command.setPassword("Short1!");

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            authService.register(command);
        });

        assertTrue(exception.getMessage().contains("密码长度不能少于8位"));
    }

    @Test
    @DisplayName("CR-001: 弱密码注册被拒绝 - 缺少大写字母")
    void register_rejectsWeakPassword_noUppercase() {
        RegisterCommand command = new RegisterCommand();
        command.setUsername("testuser");
        command.setEmail("test@example.com");
        command.setPassword("password123!");

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            authService.register(command);
        });

        assertTrue(exception.getMessage().contains("密码必须包含大写字母"));
    }

    @Test
    @DisplayName("CR-001: 弱密码注册被拒绝 - 缺少小写字母")
    void register_rejectsWeakPassword_noLowercase() {
        RegisterCommand command = new RegisterCommand();
        command.setUsername("testuser");
        command.setEmail("test@example.com");
        command.setPassword("PASSWORD123!");

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            authService.register(command);
        });

        assertTrue(exception.getMessage().contains("密码必须包含小写字母"));
    }

    @Test
    @DisplayName("CR-001: 弱密码注册被拒绝 - 缺少数字")
    void register_rejectsWeakPassword_noDigit() {
        RegisterCommand command = new RegisterCommand();
        command.setUsername("testuser");
        command.setEmail("test@example.com");
        command.setPassword("Password!");

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            authService.register(command);
        });

        assertTrue(exception.getMessage().contains("密码必须包含数字"));
    }

    @Test
    @DisplayName("CR-001: 弱密码注册被拒绝 - 缺少特殊字符")
    void register_rejectsWeakPassword_noSpecial() {
        RegisterCommand command = new RegisterCommand();
        command.setUsername("testuser");
        command.setEmail("test@example.com");
        command.setPassword("Password123");

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            authService.register(command);
        });

        assertTrue(exception.getMessage().contains("密码必须包含特殊字符"));
    }

    @Test
    @DisplayName("CR-001: 符合策略的密码可以通过密码策略校验")
    void register_acceptsStrongPassword() {
        RegisterCommand command = new RegisterCommand();
        command.setUsername("testuser");
        command.setEmail("test@example.com");
        command.setPassword("StrongP@ss123");

        // 验证不会抛出密码策略异常
        // 注意：由于缺少其他依赖（如角色分配），这里只验证密码策略部分
        assertDoesNotThrow(() -> {
            try {
                authService.register(command);
            } catch (BusinessException e) {
                // 忽略非密码策略相关的异常（如数据库相关）
                if (e.getMessage() != null && e.getMessage().contains("密码")) {
                    throw e;
                }
            } catch (Exception e) {
                // 忽略其他异常
            }
        });
    }
}
