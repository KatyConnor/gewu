package com.gewu.application.requirement;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.requirement.Requirement;
import com.gewu.domain.requirement.RequirementFile;
import com.gewu.infrastructure.mapper.RequirementFileMapper;
import com.gewu.infrastructure.mapper.RequirementMapper;
import com.gewu.infrastructure.storage.MinioStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

/**
 * 需求文件空间服务 - 管理需求的 docs/tests/reports 文件.
 * <p>MinIO 路径: projects/{projectId}/requirements/{requirementId}/{category}/{fileName}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementFileService {

    private final RequirementFileMapper fileMapper;
    private final RequirementMapper requirementMapper;
    private final MinioStorageService storageService;

    private static final Set<String> VALID_CATEGORIES = Set.of("docs", "tests", "reports");
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024; // 50MB

    /** 上传文件 */
    @Transactional
    public RequirementFile uploadFile(String requirementId, String category, MultipartFile file) {
        validateCategory(category);
        Requirement req = getRequirementEntity(requirementId);

        if (file.getSize() > MAX_FILE_SIZE) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "文件超过 50MB 限制");
        }

        String objectKey = String.format("projects/%s/requirements/%s/%s/%s",
                req.getProjectId(), requirementId, category, file.getOriginalFilename());

        storageService.uploadWorkspaceFile(objectKey, file);

        RequirementFile rf = new RequirementFile();
        rf.setRequirementId(requirementId);
        rf.setCategory(category);
        rf.setFileName(file.getOriginalFilename());
        rf.setObjectKey(objectKey);
        rf.setMimeType(file.getContentType());
        rf.setFileSize(file.getSize());
        rf.setVersion(1);
        rf.setCreatedBy(UserContext.currentUserId());
        fileMapper.insert(rf);

        log.info("需求文件上传: requirementId={}, category={}, file={}", requirementId, category, file.getOriginalFilename());
        return rf;
    }

    /**
     * 从文本内容创建需求文件（AI 生成文件专用）。
     * <p>
     * 不需要 MultipartFile，适用于 AI 推理结果直接保存为需求文件。
     *
     * @param requirementId 需求 ID
     * @param category      文件分类（docs / tests / reports）
     * @param fileName      文件名
     * @param content       文件文本内容
     * @param mimeType      MIME 类型
     * @return 创建的需求文件实体（含 objectKey）
     * @since 1.0.0
     */
    @Transactional
    public RequirementFile createFileFromContent(String requirementId, String category,
                                                  String fileName, String content, String mimeType) {
        validateCategory(category);
        Requirement req = getRequirementEntity(requirementId);

        long fileSize = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (fileSize > MAX_FILE_SIZE) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "文件超过 50MB 限制");
        }

        String objectKey = String.format("projects/%s/requirements/%s/%s/%s",
                req.getProjectId(), requirementId, category, fileName);

        storageService.uploadWorkspaceContent(objectKey, content, mimeType);

        RequirementFile rf = new RequirementFile();
        rf.setRequirementId(requirementId);
        rf.setCategory(category);
        rf.setFileName(fileName);
        rf.setObjectKey(objectKey);
        rf.setMimeType(mimeType);
        rf.setFileSize(fileSize);
        rf.setVersion(1);
        rf.setCreatedBy(UserContext.currentUserId());
        fileMapper.insert(rf);

        log.info("AI 需求文件已创建: requirementId={}, category={}, file={}", requirementId, category, fileName);
        return rf;
    }

    /**
     * 从二进制内容创建需求文件（AI 生成 Word 文档等二进制文件专用）。
     *
     * @param requirementId 需求 ID
     * @param category      文件分类
     * @param fileName      文件名
     * @param data          文件二进制内容
     * @param mimeType      MIME 类型
     * @return 创建的需求文件实体
     * @since 1.0.0
     */
    @Transactional
    public RequirementFile createFileFromBytes(String requirementId, String category,
                                                String fileName, byte[] data, String mimeType) {
        validateCategory(category);
        Requirement req = getRequirementEntity(requirementId);

        if (data.length > MAX_FILE_SIZE) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "文件超过 50MB 限制");
        }

        String objectKey = String.format("projects/%s/requirements/%s/%s/%s",
                req.getProjectId(), requirementId, category, fileName);

        storageService.uploadWorkspaceBytes(objectKey, data, mimeType);

        RequirementFile rf = new RequirementFile();
        rf.setRequirementId(requirementId);
        rf.setCategory(category);
        rf.setFileName(fileName);
        rf.setObjectKey(objectKey);
        rf.setMimeType(mimeType);
        rf.setFileSize((long) data.length);
        rf.setVersion(1);
        rf.setCreatedBy(UserContext.currentUserId());
        fileMapper.insert(rf);

        log.info("AI 二进制需求文件已创建: requirementId={}, category={}, file={}", requirementId, category, fileName);
        return rf;
    }

    /** 文件列表（按分类） */
    public List<RequirementFile> listFiles(String requirementId, String category) {
        LambdaQueryWrapper<RequirementFile> wrapper = new LambdaQueryWrapper<RequirementFile>()
                .eq(RequirementFile::getRequirementId, requirementId);
        if (category != null && !category.isBlank()) {
            validateCategory(category);
            wrapper.eq(RequirementFile::getCategory, category);
        }
        return fileMapper.selectList(wrapper.orderByDesc(RequirementFile::getCreatedAt));
    }

    /** 读取文件内容 */
    public String getFileContent(String requirementId, String fileId) {
        RequirementFile rf = getFileEntity(requirementId, fileId);
        return storageService.getContent(rf.getObjectKey());
    }

    /** 删除文件 */
    @Transactional
    public void deleteFile(String requirementId, String fileId) {
        RequirementFile rf = getFileEntity(requirementId, fileId);
        storageService.deleteObject(rf.getObjectKey());
        fileMapper.deleteById(fileId);
    }

    /** 获取下载 URL */
    public String getDownloadUrl(String requirementId, String fileId) {
        RequirementFile rf = getFileEntity(requirementId, fileId);
        return storageService.getPresignedUrl(rf.getObjectKey());
    }

    // ==================== 辅助 ====================

    private void validateCategory(String category) {
        if (category == null || !VALID_CATEGORIES.contains(category)) {
            throw BusinessException.of(ResultCode.PARAM_INVALID,
                    "文件分类无效，可选: docs/tests/reports");
        }
    }

    private Requirement getRequirementEntity(String requirementId) {
        Requirement req = requirementMapper.selectById(requirementId);
        if (req == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "需求不存在");
        }
        return req;
    }

    private RequirementFile getFileEntity(String requirementId, String fileId) {
        RequirementFile rf = fileMapper.selectById(fileId);
        if (rf == null || !rf.getRequirementId().equals(requirementId)) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "文件不存在或无权访问");
        }
        return rf;
    }
}
