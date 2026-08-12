package com.gewu.application.wenshi.output;

import com.gewu.application.ai.ModelConfigService;
import com.gewu.application.requirement.RequirementFileService;
import com.gewu.application.workspace.WorkspaceService;
import com.gewu.application.workspace.dto.FileNodeDTO;
import com.gewu.domain.requirement.RequirementFile;
import com.gewu.infrastructure.llm.LlmClient;
import com.gewu.infrastructure.llm.LlmClientFactory;
import com.gewu.infrastructure.llm.LlmRequest;
import com.gewu.infrastructure.llm.LlmResponse;
import com.gewu.infrastructure.llm.Message;
import com.gewu.infrastructure.storage.MinioStorageService;
import com.gewu.infrastructure.storage.WordDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 文件输出服务 - 将 AI 推理结果保存为文件并生成摘要。
 * <p>
 * 存储路由：
 * <ul>
 *   <li>有 requirementId -> {@link RequirementFileService}（项目需求空间，MinIO）</li>
 *   <li>无 requirementId -> {@link WorkspaceService}（用户工作空间，MinIO）</li>
 * </ul>
 * 生成摘要：调用 LLM 对完整内容生成 3-5 句话概括，替代原始长文本输出到对话框。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileOutputService {

    private final WorkspaceService workspaceService;
    private final RequirementFileService requirementFileService;
    private final MinioStorageService storageService;
    private final WordDocumentService wordDocumentService;
    private final LlmClientFactory llmClientFactory;
    private final ModelConfigService modelConfigService;

    @Value("${gewu.wenshi.llm.default-provider:qwen}")
    private String defaultLlmProvider;

    @Value("${gewu.wenshi.llm.default-model:qwen-plus}")
    private String defaultLlmModel;

    /**
     * 将文件产物列表保存到存储，并返回文件信息（含下载 URL）。
     *
     * @param artifacts    文件产物列表
     * @param projectId    项目 ID（可为 null）
     * @param requirementId 需求 ID（可为 null）
     * @return 文件信息列表（含下载 URL 和预览内容）
     * @since 1.0.0
     */
    public List<FileInfo> generateFiles(List<FileArtifact> artifacts,
                                         String projectId, String requirementId) {
        List<FileInfo> results = new ArrayList<>();
        for (FileArtifact artifact : artifacts) {
            try {
                FileInfo info = saveArtifact(artifact, projectId, requirementId);
                results.add(info);
            } catch (Exception e) {
                log.error("FileOutputService: 保存文件失败: {}", e.getMessage(), e);
            }
        }
        return results;
    }

    /**
     * 生成内容摘要（LLM 调用，3-5 句话概括）。
     *
     * @param fullContent 完整内容
     * @param model       模型 ID
     * @return 摘要文本
     * @since 1.0.0
     */
    public String generateSummary(String fullContent, String model) {
        try {
            String[] pm = resolveProviderAndModel(model);
            LlmClient client = llmClientFactory.getClient(pm[0]);
            String truncated = fullContent.length() > 6000
                    ? fullContent.substring(0, 6000) : fullContent;
            LlmRequest request = LlmRequest.builder()
                    .model(pm[1])
                    .messages(List.of(
                            Message.builder().role("system").content(
                                    "你是内容摘要生成器。请为以下内容生成一个结构化的核心摘要，包含：\n" +
                                    "1. **整体概要**（1-2句话说明做了什么）\n" +
                                    "2. **关键步骤**（列出主要步骤，每步一句话）\n" +
                                    "3. **核心代码片段**（如有代码，摘取最关键的5-10行，用代码块包裹）\n" +
                                    "4. **关键结论**（总结要点）\n" +
                                    "保持简洁但信息完整，让用户能从摘要中了解整体结果的核心内容。\n" +
                                    "最后添加一行：「📄 完整内容已保存为文件，可点击下方文件卡片查看或下载。」").build(),
                            Message.builder().role("user").content(truncated).build()))
                    .maxTokens(2048)
                    .stream(false)
                    .build();
            LlmResponse response = client.chat(request);
            return response.getContent() != null ? response.getContent() : "内容已保存为文件。";
        } catch (Exception e) {
            log.warn("FileOutputService: 摘要生成失败，使用截取摘要: {}", e.getMessage());
            // 降级：截取前 500 字符作为摘要
            String fallback = fullContent.length() > 500
                    ? fullContent.substring(0, 500) + "...\n\n完整内容已保存为文件，可点击下方文件卡片查看或下载。"
                    : fullContent;
            return fallback;
        }
    }

    /**
     * 保存单个文件产物到存储。
     */
    private FileInfo saveArtifact(FileArtifact artifact, String projectId, String requirementId) {
        String fileName = generateFileName(artifact);
        String mimeType = resolveMimeType(artifact);
        boolean isDocx = "docx".equals(artifact.getFileType());

        // Word 文档：Markdown -> .docx 二进制
        byte[] binaryData = null;
        String textContent = artifact.getContent();
        long fileSize;

        if (isDocx) {
            binaryData = wordDocumentService.markdownToWord(artifact.getContent());
            fileSize = binaryData.length;
        } else {
            fileSize = textContent.getBytes(StandardCharsets.UTF_8).length;
        }

        String downloadUrl;
        if (requirementId != null && !requirementId.isBlank()) {
            // 项目需求空间
            RequirementFile rf;
            if (isDocx) {
                rf = requirementFileService.createFileFromBytes(
                        requirementId, "docs", fileName, binaryData, mimeType);
            } else {
                rf = requirementFileService.createFileFromContent(
                        requirementId, "docs", fileName, textContent, mimeType);
            }
            downloadUrl = storageService.getPresignedUrl(rf.getObjectKey());
        } else {
            // 用户工作空间
            FileNodeDTO node;
            if (isDocx) {
                node = workspaceService.createFileFromBytes(fileName, binaryData, mimeType);
            } else {
                node = workspaceService.createFileFromContent(fileName, textContent, mimeType);
            }
            downloadUrl = workspaceService.getDownloadUrl(node.getFileId());
        }

        // 预览内容（取原始 Markdown 前 500 字符，而非二进制内容）
        String preview = textContent.length() > 500
                ? textContent.substring(0, 500) + "..." : textContent;

        return FileInfo.builder()
                .fileName(fileName)
                .fileType(artifact.getFileType())
                .mimeType(mimeType)
                .fileSize(fileSize)
                .downloadUrl(downloadUrl)
                .previewContent(preview)
                .source("ai")
                .build();
    }

    /**
     * 生成文件名。
     */
    private String generateFileName(FileArtifact artifact) {
        if (artifact.getSuggestedFileName() != null && !artifact.getSuggestedFileName().isBlank()) {
            return artifact.getSuggestedFileName();
        }
        String ext = resolveExtension(artifact);
        String shortId = UUID.randomUUID().toString().substring(0, 8);
        return "ai_" + artifact.getFileType() + "_" + shortId + ext;
    }

    /**
     * 根据文件类型推断扩展名。
     */
    private String resolveExtension(FileArtifact artifact) {
        if ("code".equals(artifact.getFileType()) && artifact.getLanguage() != null) {
            return switch (artifact.getLanguage().toLowerCase()) {
                case "java" -> ".java";
                case "python", "py" -> ".py";
                case "typescript", "ts" -> ".ts";
                case "javascript", "js" -> ".js";
                case "sql" -> ".sql";
                case "go" -> ".go";
                case "rust", "rs" -> ".rs";
                case "c", "cpp", "c++" -> ".cpp";
                case "shell", "bash", "sh" -> ".sh";
                default -> ".txt";
            };
        }
        return switch (artifact.getFileType()) {
            case "markdown" -> ".md";
            case "json" -> ".json";
            case "yaml" -> ".yml";
            case "csv" -> ".csv";
            case "docx" -> ".docx";
            default -> ".txt";
        };
    }

    /**
     * 根据文件类型推断 MIME 类型。
     */
    private String resolveMimeType(FileArtifact artifact) {
        return switch (artifact.getFileType()) {
            case "code" -> "text/plain";
            case "markdown" -> "text/markdown";
            case "json" -> "application/json";
            case "yaml" -> "application/yaml";
            case "csv" -> "text/csv";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> "text/plain";
        };
    }

    /**
     * 解析 LLM 提供商和模型。
     */
    private String[] resolveProviderAndModel(String model) {
        if (model != null && !model.isBlank()) {
            String providerCode = modelConfigService.getProviderCodeByModelId(model);
            if (providerCode != null) {
                return new String[]{providerCode, model};
            }
        }
        return new String[]{defaultLlmProvider, defaultLlmModel};
    }
}