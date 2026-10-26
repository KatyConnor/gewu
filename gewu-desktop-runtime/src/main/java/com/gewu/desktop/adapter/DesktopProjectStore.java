package com.gewu.desktop.adapter;

import com.gewu.desktop.store.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地项目存储（D1-25 项目分组）：local_project 的 CRUD。
 * 项目绑定本地目录（必选）——目录即工作区语义，会话创建经 projectId 关联并默认继承目录；
 * 删除项目时会话回落默认空间（project_id 置 NULL，保留 directory 绑定，文件链路不断）。
 *
 * @since 1.0.0
 */
@Component
public class DesktopProjectStore {

    private final JdbcTemplate jdbc;

    public DesktopProjectStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listProjects() {
        return jdbc.queryForList(
                "SELECT * FROM local_project ORDER BY created_at ASC").stream()
                .map(DesktopProjectStore::toProjectDTO).toList();
    }

    public Map<String, Object> getProject(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM local_project WHERE id = ?", id);
        return rows.isEmpty() ? null : toProjectDTO(rows.get(0));
    }

    /** 创建项目：目录必填且全局唯一（同目录重复建项无意义，幂等报错由端点层转换） */
    public Map<String, Object> createProject(String name, String directory) {
        return createProject(name, directory, null, null);
    }

    /** 创建项目（开发空间克隆落地）：携带 Git 来源信息（repoUrl/repoBranch） */
    public Map<String, Object> createProject(String name, String directory, String repoUrl, String repoBranch) {
        List<Map<String, Object>> dup = jdbc.queryForList(
                "SELECT id FROM local_project WHERE directory = ?", directory);
        if (!dup.isEmpty()) {
            throw new IllegalStateException("该目录已存在项目: " + directory);
        }
        String id = Ids.newId();
        long now = System.currentTimeMillis();
        jdbc.update(
                "INSERT INTO local_project (id, name, directory, repo_url, repo_branch, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
                id, name, directory, repoUrl, repoBranch, now, now);
        return getProject(id);
    }

    /** 删除项目：其会话 project_id 置 NULL 回落默认空间（directory 保留，工作区不变） */
    public int deleteProject(String id) {
        jdbc.update("UPDATE local_session SET project_id = NULL, updated_at = ? WHERE project_id = ?",
                System.currentTimeMillis(), id);
        return jdbc.update("DELETE FROM local_project WHERE id = ?", id);
    }

    /** ProjectDTO 视图（与 @gewu/agent-ui/protocol ProjectDTO 关键字段对齐——侧栏渲染读 projectName） */
    public static Map<String, Object> toProjectDTO(Map<String, Object> row) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("projectId", row.get("id"));
        dto.put("projectName", row.get("name"));
        dto.put("repoUrl", row.get("repo_url"));
        dto.put("repoBranch", row.get("repo_branch"));
        // 桌面扩展字段：项目绑定目录（会话继承为工作区；协议 ProjectDTO 无此列）
        dto.put("directory", row.get("directory"));
        dto.put("projectCode", "");
        dto.put("description", "");
        dto.put("visibility", 0);
        dto.put("status", 1);
        dto.put("ownerId", "local");
        dto.put("ownerName", "本地用户");
        dto.put("memberCount", 1);
        dto.put("createdAt", row.get("created_at"));
        return dto;
    }
}
