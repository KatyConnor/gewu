package com.gewu.desktop.endpoint;

import com.gewu.desktop.adapter.DesktopProjectStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 引擎 API Profile：开发空间（桌面本地模式，对齐 web DevWorkspacePage）。
 * Git 项目克隆（本地 git，非交互 ssh——修 Host key verification failed）+
 * 项目文件树浏览（懒加载目录/文件清单，渲染层驱动预览与终端）。
 * 克隆落地为本地项目（local_project 绑定克隆目录）——会话/文件工具/终端沿用项目工作区。
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/v1/dev-workspace")
public class DesktopDevWorkspaceController {

    private final DesktopProjectStore projectStore;

    public DesktopDevWorkspaceController(DesktopProjectStore projectStore) {
        this.projectStore = projectStore;
    }

    public record CloneRequest(String repoUrl, String branch, String projectName, String targetRoot) {
    }

    /**
     * 克隆 Git 仓库并落地为本地项目。
     * 目标目录 = targetRoot(缺省 userData/workspaces)/项目名；克隆完成创建项目记录（绑定该目录）。
     */
    @PostMapping("/clone")
    public Map<String, Object> clone(@RequestBody CloneRequest req) throws Exception {
        if (req == null || req.repoUrl == null || req.repoUrl.isBlank()) {
            throw new IllegalArgumentException("仓库地址必填");
        }
        String repoUrl = req.repoUrl.trim();
        String branch = req.branch == null || req.branch.isBlank() ? null : req.branch.trim();
        String name = (req.projectName == null || req.projectName.isBlank())
                ? deriveName(repoUrl) : req.projectName.trim();
        Path root = Path.of(req.targetRoot == null || req.targetRoot.isBlank()
                ? defaultRoot() : req.targetRoot.trim());
        Path target = root.resolve(name).toAbsolutePath().normalize();
        if (Files.exists(target)) {
            throw new IllegalStateException("目标目录已存在: " + target + "（请更换项目名或先删除既有项目）");
        }
        Files.createDirectories(root);

        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("clone");
        if (branch != null) {
            cmd.add("--branch");
            cmd.add(branch);
        }
        cmd.add(repoUrl);
        cmd.add(target.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(root.toFile());
        pb.redirectErrorStream(false);
        // 非交互环境（对齐平台修复）：自动接受新主机指纹——修桌面本地克隆 Host key verification failed
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_SSH_COMMAND",
                "ssh -o StrictHostKeyChecking=accept-new -o BatchMode=yes");
        Process proc = pb.start();
        String stderr = new String(proc.getErrorStream().readAllBytes());
        boolean finished = proc.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            proc.destroyForcibly();
            throw new IllegalStateException("克隆超时（10 分钟）: " + repoUrl);
        }
        if (proc.exitValue() != 0) {
            throw new IllegalStateException("git clone 失败: "
                    + (stderr.length() > 480 ? stderr.substring(0, 480) : stderr.trim()));
        }
        Map<String, Object> project = projectStore.createProject(name, target.toString(), repoUrl, branch);
        return project;
    }

    /** 项目文件树（懒加载）：列出项目目录下指定相对路径的一层子项（目录优先、字母序） */
    @GetMapping("/projects/{projectId}/tree")
    public List<Map<String, Object>> tree(@PathVariable String projectId,
                                           @org.springframework.web.bind.annotation.RequestParam(
                                                   value = "path", defaultValue = "") String path) throws Exception {
        Map<String, Object> project = projectStore.getProject(projectId);
        if (project == null) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
        Path root = Path.of(String.valueOf(project.get("directory"))).toRealPath();
        Path target = root.resolve(path == null ? "" : path).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("路径越界: " + path);
        }
        File[] children = target.toFile().listFiles();
        List<Map<String, Object>> items = new ArrayList<>();
        if (children == null) {
            return items;
        }
        for (File f : children) {
            String rel = root.relativize(f.toPath()).toString().replace('\\', '/');
            if (f.getName().equals(".git")) {
                continue; // 元数据目录不展示
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", f.getName());
            item.put("path", rel);
            item.put("dir", f.isDirectory());
            items.add(item);
        }
        items.sort((a, b) -> {
            boolean da = Boolean.TRUE.equals(a.get("dir"));
            boolean db = Boolean.TRUE.equals(b.get("dir"));
            if (da != db) {
                return da ? -1 : 1;
            }
            return String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name")));
        });
        return items;
    }

    /** 读取项目内文件内容（预览视图；Result<String> 直通——对齐 file-changes/content 契约，中文不损坏） */
    @GetMapping(value = "/projects/{projectId}/file", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public com.gewu.common.result.Result<String> file(@PathVariable String projectId,
                                                      @org.springframework.web.bind.annotation.RequestParam("path") String path) throws Exception {
        Map<String, Object> project = projectStore.getProject(projectId);
        if (project == null) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
        Path root = Path.of(String.valueOf(project.get("directory"))).toRealPath();
        Path target = root.resolve(path == null ? "" : path).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            throw new IllegalArgumentException("文件不存在或越界: " + path);
        }
        if (Files.size(target) > 2 * 1024 * 1024) {
            return com.gewu.common.result.Result.success("（文件超过 2MB，仅支持预览小文件）");
        }
        return com.gewu.common.result.Result.success(Files.readString(target));
    }

    private String defaultRoot() {
        return System.getProperty("user.home") + "/gewu-workspaces";
    }

    /** 从仓库地址推导项目名（末段去 .git） */
    private static String deriveName(String repoUrl) {
        String t = repoUrl.replaceAll("/+$", "");
        int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf(':'));
        String name = slash >= 0 ? t.substring(slash + 1) : t;
        return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
    }
}
