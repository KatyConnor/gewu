package com.gewu.interfaceapi.controller;

import com.gewu.application.entry.AgentEntryFileService;
import com.gewu.application.entry.EntryPackageService;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 入口调度文件接口（设计文档 56 §6.2）：
 * 用户全局文件读写（MinIO 权威源 + workspace_file 台账）、
 * 项目级裁决视图与文件回显、模板初始化、升级提示（§6.4）。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "入口调度文件", description = "AGENTS.md/GEWU.md 全局与项目级调度文件管理")
public class EntryFileController {

    private final AgentEntryFileService entryFileService;
    private final EntryPackageService entryPackageService;

    public record SaveCommand(@NotBlank String name, @NotBlank String content) {}

    public record InitCommand(@NotBlank String name, String content) {}

    /** 入口包上传体积预检上限（210MB，细校验在服务内流式执行） */
    private static final long UPLOAD_CAP_BYTES = 210L * 1024 * 1024;

    @PostMapping(value = "/entry-package/import", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "导入入口包", description = "zip（整包）或 .md（单文件）→ 全局空间或项目仓库根；dryRun=true 预览零写入；返回导入报告")
    public Result<Map<String, Object>> importPackage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "global") String target,
            @RequestParam(required = false) String projectId,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "overwrite") String conflictPolicy,
            @RequestParam(required = false) String packageId) throws Exception {
        String userId = requireUserId();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        byte[] bytes;
        try (InputStream in = file.getInputStream()) {
            bytes = in.readNBytes((int) Math.min(UPLOAD_CAP_BYTES + 1, Integer.MAX_VALUE));
        }
        if (bytes.length > UPLOAD_CAP_BYTES) {
            throw new IllegalArgumentException("上传文件超过上限（200MB）");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        var options = new EntryPackageService.ImportOptions(dryRun, conflictPolicy, packageId);
        var report = filename.toLowerCase().endsWith(".md")
                ? entryPackageService.importSingleEntry(userId, target, projectId,
                        filename.substring(filename.lastIndexOf('/') + 1), bytes, options)
                : entryPackageService.importFromZip(userId, target, projectId, bytes, options);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileCount", report.fileCount());
        out.put("entryFile", report.entryFile());
        out.put("overwritten", report.overwritten());
        out.put("target", report.target());
        out.put("dryRun", report.dryRun());
        out.put("actions", report.actions());
        return Result.success(out);
    }

    @GetMapping("/entry-package/history")
    @Operation(summary = "导入历史", description = "全局空间导入历史（JSONL 倒序，默认最近 10 条）")
    public Result<List<String>> importHistory(
            @RequestParam(defaultValue = "10") int limit) {
        String userId = requireUserId();
        return Result.success(entryPackageService.readImportHistory(userId, limit));
    }

    // ==================== Git URL 导入（P3-4 二期） ====================

    public record ImportGitCommand(@NotBlank String url, String ref,
                                   @NotBlank String target, String projectId,
                                   boolean dryRun, String conflictPolicy) {}

    @PostMapping("/entry-package/import-git")
    @Operation(summary = "从 Git 仓库导入入口包", description = "浅克隆经 dev 沙箱中转（凭证复用沙箱已注入 git 凭证）；仅 http(s)/ssh/git 协议")
    public Result<Map<String, Object>> importFromGit(@Valid @RequestBody ImportGitCommand command) {
        String userId = requireUserId();
        var options = new EntryPackageService.ImportOptions(command.dryRun(),
                command.conflictPolicy() == null || command.conflictPolicy().isBlank()
                        ? EntryPackageService.POLICY_OVERWRITE : command.conflictPolicy(),
                null);
        var report = entryPackageService.importFromGit(userId, command.target(), command.projectId(),
                command.url(), command.ref(), options);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileCount", report.fileCount());
        out.put("entryFile", report.entryFile());
        out.put("overwritten", report.overwritten());
        out.put("target", report.target());
        out.put("dryRun", report.dryRun());
        out.put("actions", report.actions());
        return Result.success(out);
    }

    // ==================== 用户全局层 ====================

    @GetMapping("/me/entry-file")
    @Operation(summary = "读取全局入口文件", description = "MinIO 权威源读取（name=GEWU.md|AGENTS.md），不存在返回 exists=false")
    public Result<Map<String, Object>> readGlobal(@RequestParam String name) {
        String userId = requireUserId();
        String content = entryFileService.readGlobalFile(userId, name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exists", content != null);
        out.put("content", content == null ? "" : content);
        return Result.success(out);
    }

    @PutMapping("/me/entry-file")
    @Operation(summary = "保存全局入口文件", description = "MinIO 直写 + workspace_file 建行 + M2V 入队（评审 F-202 闭环）")
    public Result<Map<String, Object>> saveGlobal(@Valid @RequestBody SaveCommand command) {
        String userId = requireUserId();
        entryFileService.saveGlobalFile(userId, command.name(), command.content());
        return Result.success(Map.of("saved", true, "name", command.name()));
    }

    @GetMapping("/me/entry-file/resolve")
    @Operation(summary = "全局裁决预览", description = "仅全局层裁决（content/source/meta/upgradeHint），供设置页预览")
    public Result<Map<String, Object>> resolveGlobal() {
        String userId = requireUserId();
        var view = entryFileService.resolve(userId, null);
        // 手工映射（CR F#4：record 的 present() 为派生方法不被 Jackson 序列化，meta 需对齐前端字段名）
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("present", view.present());
        out.put("source", view.source());
        out.put("content", view.content());
        out.put("meta", view.metaJson());
        out.put("upgradeHint", view.upgradeHint());
        return Result.success(out);
    }

    // ==================== 项目层 ====================

    @GetMapping("/projects/{projectId}/entry-file")
    @Operation(summary = "项目入口裁决视图", description = "全局+项目跨层裁决结果（含 §6.4 升级提示），供项目调度文件页签")
    public Result<Map<String, Object>> resolveProject(@PathVariable String projectId) {
        requireUserId();
        AgentEntryFileService.EntryView view = entryFileService.resolveForProject(projectId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("present", view.present());
        out.put("source", view.source());
        out.put("content", view.content());
        out.put("meta", view.metaJson());
        out.put("upgradeHint", view.upgradeHint());
        return Result.success(out);
    }

    @GetMapping("/projects/{projectId}/entry-file/file")
    @Operation(summary = "读取项目入口文件", description = "沙箱卷内项目仓库根文件回显（name=GEWU.md|AGENTS.md）")
    public Result<Map<String, Object>> readProject(@PathVariable String projectId,
                                                   @RequestParam String name) {
        requireUserId();
        return Result.success(entryFileService.readProjectFile(projectId, name));
    }

    @PostMapping("/projects/{projectId}/entry-file/init")
    @Operation(summary = "初始化项目入口文件", description = "写入平台模板（仅 clone 成功后可调——clone 要求目标目录为空）")
    public Result<Map<String, Object>> initProject(@PathVariable String projectId,
                                                   @Valid @RequestBody InitCommand command) {
        requireUserId();
        String content = command.content() != null && !command.content().isBlank()
                ? command.content()
                : defaultTemplate(command.name());
        entryFileService.initProjectFile(projectId, command.name(), content);
        return Result.success(Map.of("initialized", true, "name", command.name()));
    }

    // ==================== 内部 ====================

    private static String requireUserId() {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw com.gewu.common.result.BusinessException.of(com.gewu.common.result.ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    /** 平台模板（治理骨架 + 版本头协议注释，设计文档 56 §6.2 两档模板的治理档）。 */
    private static String defaultTemplate(String name) {
        return "---\n"
                + "description: 项目协作规范（格物智能体调度入口）\n"
                + "version: 1.0.0\n"
                + "---\n\n"
                + "# " + name + " · 项目调度入口\n\n"
                + "> 版本协议说明：全局与项目两侧都声明 version 时，版本高者生效；\n"
                + "> 仅项目侧声明（或都不声明）时项目级优先。修改内容请同步递增版本号。\n\n"
                + "## 项目概述\n\n（一句话说明本项目是什么）\n\n"
                + "## 构建与测试命令\n\n```bash\n# 构建命令\n# 测试命令\n```\n\n"
                + "## 代码风格\n\n- \n\n"
                + "## 规范与技能索引\n\n- \n\n"
                + "## 安全红线\n\n- 凭证三禁：不硬编码、不提交、不外传\n- \n";
    }
}
