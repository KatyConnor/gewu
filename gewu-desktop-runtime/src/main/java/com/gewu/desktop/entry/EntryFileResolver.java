package com.gewu.desktop.entry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.common.entry.EntryCandidate;
import com.gewu.common.entry.EntryFileSelector;
import com.gewu.common.entry.EntrySelection;
import com.gewu.desktop.config.DesktopProps;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 入口调度文件解析器（桌面端）— 发现/读取/裁决 ~/.gewu 全局层与项目根项目层.
 *
 * <p>发现顺序每层固定 GEWU.md → AGENTS.md（命中即停）；跨层裁决用平台同款纯函数
 * {@link EntryFileSelector}（设计文档 56 §5.2：默认项目级优先，双方声明版本才比高低）。
 * 注入与 {@code project.context} 工具共用本解析器，保证两处看到同一份入口文件。
 *
 * @since 1.0.0
 */
@Component
public class EntryFileResolver {

    /** 单文件读取上限（字节，设计文档 56 §5.1） */
    private static final int READ_CAP_BYTES = 64 * 1024;

    private final DesktopProps props;
    private final ObjectMapper objectMapper;

    public EntryFileResolver(DesktopProps props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * 裁决结果：content=null 表示无入口文件（source=none）.
     */
    public record EntryResolution(String content, String source, String metaJson) {
        public boolean present() {
            return content != null && !content.isEmpty();
        }
    }

    /**
     * 解析入口调度文件。projectDirectory 为 null 时仅裁决全局层.
     */
    public EntryResolution resolve(String projectDirectory) {
        EntryCandidate global = load("user-global", Path.of(
                System.getProperty("user.home"), ".gewu"));
        EntryCandidate project = projectDirectory == null || projectDirectory.isBlank()
                ? null
                : load("project", Path.of(projectDirectory));

        EntrySelection sel = EntryFileSelector.select(global, project);
        if (sel.getContent() == null) {
            return new EntryResolution(null, "none", metaJson("none", null, sel.getReason(), "", 0, false));
        }

        // 注入总量上限（字节），超限截断并标记
        int maxBytes = Math.max(1, props.getEntryMaxBytes());
        byte[] raw = sel.getContent().getBytes(StandardCharsets.UTF_8);
        String content = sel.getContent();
        boolean truncated = false;
        if (raw.length > maxBytes) {
            content = truncateToByteLimit(sel.getContent(), maxBytes) + "\n\n…（入口调度指令超限截断）";
            truncated = true;
        }
        String meta = metaJson(sel.getSource(), sel.getVersion(), sel.getReason(),
                sha256Hex(content.getBytes(StandardCharsets.UTF_8)),
                content.getBytes(StandardCharsets.UTF_8).length, truncated);
        return new EntryResolution(content, sel.getSource(), meta);
    }

    /** 供 project.context 复用的裁决读取（不截断注入上限，由工具自身预算控制）. */
    public EntrySelection resolveSelection(String projectDirectory) {
        EntryCandidate global = load("user-global", Path.of(
                System.getProperty("user.home"), ".gewu"));
        EntryCandidate project = projectDirectory == null || projectDirectory.isBlank()
                ? null
                : load("project", Path.of(projectDirectory));
        return EntryFileSelector.select(global, project);
    }

    /** 单层发现：dir/GEWU.md → dir/AGENTS.md，命中即停；读取上限 64KB。 */
    private EntryCandidate load(String level, Path dir) {
        for (String name : new String[]{"GEWU.md", "AGENTS.md"}) {
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                byte[] bytes = Files.readAllBytes(file);
                String content = new String(bytes, StandardCharsets.UTF_8);
                if (bytes.length > READ_CAP_BYTES) {
                    content = truncateToByteLimit(content, READ_CAP_BYTES);
                }
                return EntryCandidate.builder()
                        .level(level)
                        .path(level + ":" + name)
                        .version(EntryFileSelector.extractVersion(content))
                        .content(content)
                        .build();
            } catch (IOException e) {
                // 单层读取失败按该层不存在处理（设计文档 56 §6.1 降级语义）
                return null;
            }
        }
        return null;
    }

    /** 按字节上限截断（不切半个多字节字符）。 */
    private static String truncateToByteLimit(String content, int maxBytes) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return content;
        }
        int cut = maxBytes;
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
            cut--;
        }
        return new String(bytes, 0, cut, StandardCharsets.UTF_8);
    }

    private String metaJson(String source, String version, String reason, String sha256, int bytes, boolean truncated) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("source", source);
            meta.put("version", version);
            meta.put("reason", reason);
            meta.put("sha256", sha256);
            meta.put("bytes", bytes);
            meta.put("truncated", truncated);
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return "{\"source\":\"" + source + "\",\"reason\":\"" + reason + "\"}";
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            return "";
        }
    }
}
