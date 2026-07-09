package com.gewu.common.jwt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    private static final String SECRET = "test-secret-key-at-least-32-characters-long";
    private static final long ACCESS_EXPIRATION = 1800000L;
    private static final long REFRESH_EXPIRATION = 604800000L;

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, ACCESS_EXPIRATION, REFRESH_EXPIRATION);
    }

    @Test
    @DisplayName("generateAccessToken 返回包含用户信息的有效 JWT")
    void generateAccessTokenReturnsValidJwtWithUserInfo() {
        String token = jwtUtil.generateAccessToken("user-123", "张三", List.of("ADMIN", "USER"));
        assertThat(token).isNotNull().isNotEmpty();
        assertThat(token.split("\\.")).hasSize(3);
        assertThat(jwtUtil.getUserIdFromToken(token)).isEqualTo("user-123");
        assertThat(jwtUtil.getUsernameFromToken(token)).isEqualTo("张三");
    }

    @Test
    @DisplayName("parseToken 提取正确的 userId 和 username")
    void parseTokenExtractsCorrectUserIdAndUsername() {
        String token = jwtUtil.generateAccessToken("user-456", "李四", List.of("USER"));
        var claims = jwtUtil.parseToken(token);
        assertThat(claims.getSubject()).isEqualTo("user-456");
        assertThat(claims.get("username", String.class)).isEqualTo("李四");
    }

    @Test
    @DisplayName("validateToken 对有效令牌返回 true")
    void validateTokenReturnsTrueForValidToken() {
        String token = jwtUtil.generateAccessToken("user-789", "王五", List.of("ADMIN"));
        assertThat(jwtUtil.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("validateToken 对过期令牌返回 false")
    void validateTokenReturnsFalseForExpiredToken() throws InterruptedException {
        JwtUtil shortLived = new JwtUtil(SECRET, 1L, 1L);
        String token = shortLived.generateAccessToken("user-exp", "过期用户", List.of("USER"));
        Thread.sleep(50);
        assertThat(shortLived.validateToken(token)).isFalse();
    }

    @Test
    @DisplayName("validateToken 对篡改令牌返回 false")
    void validateTokenReturnsFalseForTamperedToken() {
        String token = jwtUtil.generateAccessToken("user-tamper", "篡改测试", List.of("USER"));
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        assertThat(jwtUtil.validateToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("validateToken 对空字符串抛出 IllegalArgumentException")
    void validateTokenThrowsForEmptyString() {
        assertThatThrownBy(() -> jwtUtil.validateToken(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getRolesFromToken 返回正确的角色列表")
    void getRolesFromTokenReturnsCorrectRoles() {
        List<String> roles = List.of("ADMIN", "DEVELOPER", "USER");
        String token = jwtUtil.generateAccessToken("user-roles", "角色用户", roles);
        assertThat(jwtUtil.getRolesFromToken(token))
                .containsExactly("ADMIN", "DEVELOPER", "USER");
    }

    @Test
    @DisplayName("isAccessToken 对访问令牌返回 true，对刷新令牌返回 false")
    void isAccessTokenReturnsTrueForAccessTokenFalseForRefresh() {
        String accessToken = jwtUtil.generateAccessToken("user-a", "用户A", List.of("USER"));
        String refreshToken = jwtUtil.generateRefreshToken("user-a", "用户A");
        assertThat(jwtUtil.isAccessToken(accessToken)).isTrue();
        assertThat(jwtUtil.isAccessToken(refreshToken)).isFalse();
    }

    @Test
    @DisplayName("isRefreshToken 对刷新令牌返回 true，对访问令牌返回 false")
    void isRefreshTokenReturnsTrueForRefreshTokenFalseForAccess() {
        String accessToken = jwtUtil.generateAccessToken("user-b", "用户B", List.of("USER"));
        String refreshToken = jwtUtil.generateRefreshToken("user-b", "用户B");
        assertThat(jwtUtil.isRefreshToken(refreshToken)).isTrue();
        assertThat(jwtUtil.isRefreshToken(accessToken)).isFalse();
    }

    @Test
    @DisplayName("generateRefreshToken 不包含 roles 声明")
    void generateRefreshTokenDoesNotIncludeRoles() {
        String refreshToken = jwtUtil.generateRefreshToken("user-refresh", "刷新用户");
        assertThat(jwtUtil.isRefreshToken(refreshToken)).isTrue();
        assertThat(jwtUtil.getUserIdFromToken(refreshToken)).isEqualTo("user-refresh");
        assertThat(jwtUtil.getUsernameFromToken(refreshToken)).isEqualTo("刷新用户");
    }

    @Test
    @DisplayName("getAccessExpiration 返回访问令牌过期时间")
    void getAccessExpirationReturnsAccessExpiration() {
        assertThat(jwtUtil.getAccessExpiration()).isEqualTo(ACCESS_EXPIRATION);
    }

    @Test
    @DisplayName("getRefreshExpiration 返回刷新令牌过期时间")
    void getRefreshExpirationReturnsRefreshExpiration() {
        assertThat(jwtUtil.getRefreshExpiration()).isEqualTo(REFRESH_EXPIRATION);
    }
}
