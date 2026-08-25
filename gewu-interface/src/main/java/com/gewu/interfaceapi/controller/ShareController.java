package com.gewu.interfaceapi.controller;

import com.gewu.application.session.SessionService;
import com.gewu.application.session.dto.SessionDTO;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话分享公开访问接口（T3.3）。
 * <p>按 slug 免鉴权只读分享的会话元数据（脱敏：不含创建者 ID）；
 * 消息内容经 MessageController 的公开会话读路径获取。
 * 网关 skip-paths 需包含 {@code /api/v1/share/**}。
 */
@RestController
@RequestMapping("/api/v1/share")
@RequiredArgsConstructor
@Tag(name = "会话分享", description = "分享链接的免鉴权只读访问")
public class ShareController {

    private final SessionService sessionService;

    @GetMapping("/{slug}")
    @Operation(summary = "按分享链接读取会话", description = "免鉴权读取公开分享的会话元数据（脱敏）")
    public Result<SessionDTO> getBySlug(@PathVariable String slug) {
        return Result.success(sessionService.getSharedSession(slug));
    }
}
