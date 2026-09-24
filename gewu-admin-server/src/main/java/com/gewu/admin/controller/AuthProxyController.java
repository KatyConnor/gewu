package com.gewu.admin.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 认证透传（后台管理端）：管理端登录仍由主应用完成（账号体系唯一来源），
 * admin-server 将 auth 端点转发到主应用并透传响应（含 Set-Cookie）。
 * <p>JWT 与主应用同密钥签发，管理端过滤器直接校验——管理端只需依赖本服务一个后端。
 * <p>透传的端点：login / register / refresh / logout（register 仅透传，
 * 用户账号管理走本服务的管理接口）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
public class AuthProxyController {

    private final String mainApiBase;

    public AuthProxyController(@Value("${gewu.admin.main-api-base:http://localhost:8081}") String mainApiBase) {
        this.mainApiBase = StringUtils.hasText(mainApiBase) ? mainApiBase : "http://localhost:8081";
    }

    @PostMapping("/login")
    public ResponseEntity<byte[]> login(@RequestBody(required = false) byte[] body,
                                        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return forward(HttpMethod.POST, "/api/v1/auth/login", body, authorization);
    }

    @PostMapping("/register")
    public ResponseEntity<byte[]> register(@RequestBody(required = false) byte[] body) {
        return forward(HttpMethod.POST, "/api/v1/auth/register", body, null);
    }

    @PostMapping("/refresh")
    public ResponseEntity<byte[]> refresh(@RequestBody(required = false) byte[] body,
                                          @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return forward(HttpMethod.POST, "/api/v1/auth/refresh", body, authorization);
    }

    @PostMapping("/logout")
    public ResponseEntity<byte[]> logout(@RequestBody(required = false) byte[] body,
                                         @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return forward(HttpMethod.POST, "/api/v1/auth/logout", body, authorization);
    }

    /** 转发到主应用同路径：透传请求体与 Authorization，回传状态行/响应体/Content-Type/Set-Cookie。 */
    private ResponseEntity<byte[]> forward(HttpMethod method, String path, byte[] body, String authorization) {
        try {
            RestClient.RequestBodySpec spec = RestClient.create()
                    .method(method)
                    .uri(mainApiBase + path)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            if (StringUtils.hasText(authorization)) {
                spec.header(HttpHeaders.AUTHORIZATION, authorization);
            }
            byte[] payload = body != null ? body : new byte[0];
            return spec
                    .body(payload)
                    .exchange((req, res) -> {
                        HttpHeaders out = new HttpHeaders();
                        org.springframework.http.MediaType ct = res.getHeaders().getContentType();
                        out.setContentType(ct != null ? ct : MediaType.APPLICATION_JSON);
                        List<String> cookies = res.getHeaders().get(HttpHeaders.SET_COOKIE);
                        if (cookies != null) {
                            out.put(HttpHeaders.SET_COOKIE, cookies);
                        }
                        byte[] respBody = res.getBody() != null ? res.getBody().readAllBytes() : new byte[0];
                        return ResponseEntity.status(res.getStatusCode()).headers(out).body(respBody);
                    });
        } catch (Exception e) {
            log.error("认证转发失败: path={}, cause={}", path, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"code\":10005,\"message\":\"认证服务不可用，请稍后重试\",\"data\":null}"
                            .getBytes(StandardCharsets.UTF_8));
        }
    }
}
