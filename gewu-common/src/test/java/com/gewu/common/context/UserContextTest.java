package com.gewu.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserContextTest {

    @BeforeEach
    void setUp() {
        UserContext.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private UserContext buildContext() {
        return UserContext.builder()
                .userId("user-001")
                .username("testuser")
                .displayName("测试用户")
                .avatarUrl("https://example.com/avatar.png")
                .roleCodes(List.of("ADMIN", "USER"))
                .permissions(Set.of("project:create", "project:read", "session:write"))
                .ipAddress("127.0.0.1")
                .userAgent("Mozilla/5.0")
                .token("access-token-value")
                .build();
    }

    @Test
    @DisplayName("set 和 get 返回相同的上下文")
    void setAndGetReturnsSameContext() {
        UserContext ctx = buildContext();
        UserContext.set(ctx);
        assertThat(UserContext.get()).isSameAs(ctx);
    }

    @Test
    @DisplayName("clear 移除当前线程的上下文")
    void clearRemovesTheContext() {
        UserContext.set(buildContext());
        assertThat(UserContext.get()).isNotNull();
        UserContext.clear();
        assertThat(UserContext.get()).isNull();
    }

    @Test
    @DisplayName("isLogin 初始时返回 false，设置后返回 true")
    void isLoginReturnsFalseInitiallyTrueAfterSet() {
        assertThat(UserContext.isLogin()).isFalse();
        UserContext.set(buildContext());
        assertThat(UserContext.isLogin()).isTrue();
    }

    @Test
    @DisplayName("currentUserId 初始时返回 null，设置后返回 userId")
    void currentUserIdReturnsNullInitiallyReturnsUserIdAfterSet() {
        assertThat(UserContext.currentUserId()).isNull();
        UserContext.set(buildContext());
        assertThat(UserContext.currentUserId()).isEqualTo("user-001");
    }

    @Test
    @DisplayName("currentUsername 初始时返回 null，设置后返回 username")
    void currentUsernameReturnsNullInitiallyReturnsUsernameAfterSet() {
        assertThat(UserContext.currentUsername()).isNull();
        UserContext.set(buildContext());
        assertThat(UserContext.currentUsername()).isEqualTo("testuser");
    }

    @Test
    @DisplayName("hasPermission 存在权限时返回 true，不存在时返回 false")
    void hasPermissionReturnsTrueWhenExistsFalseOtherwise() {
        UserContext ctx = buildContext();
        assertThat(ctx.hasPermission("project:create")).isTrue();
        assertThat(ctx.hasPermission("project:delete")).isFalse();
    }

    @Test
    @DisplayName("hasRole 存在角色时返回 true，不存在时返回 false")
    void hasRoleReturnsTrueWhenExistsFalseOtherwise() {
        UserContext ctx = buildContext();
        assertThat(ctx.hasRole("ADMIN")).isTrue();
        assertThat(ctx.hasRole("GUEST")).isFalse();
    }

    @Test
    @DisplayName("hasPermission 对 null permissions 返回 false")
    void hasPermissionReturnsFalseWhenPermissionsNull() {
        UserContext ctx = UserContext.builder()
                .userId("user-null-perm")
                .username("noperm")
                .build();
        assertThat(ctx.hasPermission("any:permission")).isFalse();
    }

    @Test
    @DisplayName("hasRole 对 null roleCodes 返回 false")
    void hasRoleReturnsFalseWhenRoleCodesNull() {
        UserContext ctx = UserContext.builder()
                .userId("user-null-role")
                .username("norole")
                .build();
        assertThat(ctx.hasRole("ADMIN")).isFalse();
    }

    @Test
    @DisplayName("Builder 正确设置所有字段")
    void builderSetsAllFields() {
        UserContext ctx = buildContext();
        assertThat(ctx.getUserId()).isEqualTo("user-001");
        assertThat(ctx.getUsername()).isEqualTo("testuser");
        assertThat(ctx.getDisplayName()).isEqualTo("测试用户");
        assertThat(ctx.getAvatarUrl()).isEqualTo("https://example.com/avatar.png");
        assertThat(ctx.getRoleCodes()).containsExactly("ADMIN", "USER");
        assertThat(ctx.getPermissions()).containsExactlyInAnyOrder("project:create", "project:read", "session:write");
        assertThat(ctx.getIpAddress()).isEqualTo("127.0.0.1");
        assertThat(ctx.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(ctx.getToken()).isEqualTo("access-token-value");
    }

    @Test
    @DisplayName("上下文在线程间隔离")
    void contextIsThreadIsolated() throws InterruptedException {
        UserContext.set(buildContext());
        Thread thread = new Thread(() -> {
            assertThat(UserContext.isLogin()).isFalse();
            assertThat(UserContext.currentUserId()).isNull();
        });
        thread.start();
        thread.join();
        assertThat(UserContext.currentUserId()).isEqualTo("user-001");
    }
}
