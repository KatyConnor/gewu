package com.gewu.common.jwt;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * JWT 工具类 — Access Token 30 分钟 + Refresh Token 7 天.
 *
 * <p>使用 HS256 签名算法，Claims 包含 userId、username、roleCodes。
 */
@Slf4j
public class JwtUtil {

    private final SecretKey key;
    private final long accessExpiration;
    private final long refreshExpiration;

    public JwtUtil(String secret, long accessExpirationMs, long refreshExpirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpiration = accessExpirationMs;
        this.refreshExpiration = refreshExpirationMs;
    }

    public String generateAccessToken(String userId, String username, List<String> roleCodes) {
        return buildToken(userId, username, roleCodes, accessExpiration, "access");
    }

    public String generateRefreshToken(String userId, String username) {
        return buildToken(userId, username, null, refreshExpiration, "refresh");
    }

    private String buildToken(String userId, String username, List<String> roleCodes, long expiration, String tokenType) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expiration);
        JwtBuilder builder = Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .claim("type", tokenType)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key);
        if (roleCodes != null) {
            builder.claim("roles", roleCodes);
        }
        return builder.compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT 令牌已过期: {}", e.getMessage());
        } catch (JwtException e) {
            log.warn("JWT 令牌无效: {}", e.getMessage());
        }
        return false;
    }

    public String getUserIdFromToken(String token) {
        return parseToken(token).getSubject();
    }

    public String getUsernameFromToken(String token) {
        return parseToken(token).get("username", String.class);
    }

    @SuppressWarnings("unchecked")
    public List<String> getRolesFromToken(String token) {
        return parseToken(token).get("roles", List.class);
    }

    public boolean isRefreshToken(String token) {
        return "refresh".equals(parseToken(token).get("type", String.class));
    }

    public boolean isAccessToken(String token) {
        return "access".equals(parseToken(token).get("type", String.class));
    }

    public long getAccessExpiration() {
        return accessExpiration;
    }

    public long getRefreshExpiration() {
        return refreshExpiration;
    }
}