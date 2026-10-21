package com.gewu.desktop.endpoint;

import com.gewu.desktop.adapter.DesktopProjectStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 引擎 API Profile：本地项目域（D1-25 项目分组）。
 * 项目绑定本地目录（必选）——新建会话挂项目时默认继承目录作为工作区；
 * 删除项目其会话回落默认空间。与渲染层「新建项目对话框 + SessionSidebar 项目分组」配套。
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/v1/projects")
public class DesktopProjectController {

    private final DesktopProjectStore store;

    public DesktopProjectController(DesktopProjectStore store) {
        this.store = store;
    }

    public record CreateProjectRequest(String name, String directory) {
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return store.listProjects();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateProjectRequest req) {
        if (req == null || req.directory() == null || req.directory().isBlank()) {
            throw new IllegalArgumentException("项目必须绑定本地目录");
        }
        String name = req.name() == null || req.name().isBlank()
                ? basename(req.directory()) : req.name().trim();
        return store.createProject(name, req.directory().trim());
    }

    @DeleteMapping("/{projectId}")
    public Map<String, Object> delete(@PathVariable String projectId) {
        int removed = store.deleteProject(projectId);
        if (removed == 0) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
        return Map.of("ok", true);
    }

    /** 名称缺省取目录名（对齐桌面目录选择器的 workspace basename 口径） */
    private static String basename(String dir) {
        String[] parts = dir.replaceAll("[\\\\/]+$", "").split("[\\\\/]");
        String last = parts.length > 0 ? parts[parts.length - 1] : "";
        return last.isBlank() ? "新项目" : last;
    }
}
