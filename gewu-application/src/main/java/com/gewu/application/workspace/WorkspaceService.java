package com.gewu.application.workspace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.workspace.dto.CreateDirCommand;
import com.gewu.application.workspace.dto.FileNodeDTO;
import com.gewu.application.workspace.dto.RenameFileCommand;
import com.gewu.application.workspace.dto.WorkspaceDTO;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.user.Role;
import com.gewu.domain.user.UserRole;
import com.gewu.domain.workspace.Workspace;
import com.gewu.domain.workspace.WorkspaceFile;
import com.gewu.infrastructure.mapper.RoleMapper;
import com.gewu.infrastructure.mapper.UserRoleMapper;
import com.gewu.infrastructure.mapper.WorkspaceFileMapper;
import com.gewu.infrastructure.mapper.WorkspaceMapper;
import com.gewu.infrastructure.storage.MinioStorageService;
import com.gewu.common.dto.sandbox.CreateSandboxCommand;
import com.gewu.common.dto.sandbox.SandboxDTO;
import com.gewu.application.sandbox.SandboxClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 用户工作空间服务 - 文件树管理、配额控制、沙箱集成.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceFileMapper fileMapper;
    private final MinioStorageService storageService;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final SandboxClient sandboxClient;

    private static final long DEFAULT_QUOTA = 1L * 1024 * 1024 * 1024;       // 1GB
    private static final long DEV_QUOTA = 5L * 1024 * 1024 * 1024;           // 5GB
    private static final long ADMIN_QUOTA = 20L * 1024 * 1024 * 1024;        // 20GB
    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024;            // 100MB
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            ".exe", ".bat", ".cmd", ".sh", ".ps1", ".dll", ".so", ".dylib");

    // ==================== 工作空间 ====================

    /** 获取或自动创建当前用户的工作空间 */
    @Transactional
    public WorkspaceDTO getOrCreateMyWorkspace() {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        Workspace ws = workspaceMapper.selectOne(
                new LambdaQueryWrapper<Workspace>().eq(Workspace::getUserId, userId));
        if (ws == null) {
            ws = initWorkspace(userId);
        }
        return toDTO(ws);
    }

    /** 初始化工作空间 */
    @Transactional
    public Workspace initWorkspace(String userId) {
        long quota = determineQuotaByRoles(userId);

        Workspace ws = new Workspace();
        ws.setUserId(userId);
        ws.setWorkspaceName("我的工作空间");
        ws.setStoragePath("workspaces/" + userId + "/");
        ws.setQuotaBytes(quota);
        ws.setUsedBytes(0L);
        ws.setFileCount(0);
        ws.setStatus(1);
        workspaceMapper.insert(ws);

        createDefaultDirectories(ws.getId(), userId);
        log.info("工作空间初始化: userId={}, workspaceId={}, quota={}", userId, ws.getId(), quota);
        return ws;
    }

    /** 按角色确定存储配额 */
    private long determineQuotaByRoles(String userId) {
        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (userRoles.isEmpty()) return DEFAULT_QUOTA;

        List<String> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
        List<Role> roles = roleMapper.selectBatchIds(roleIds);
        List<String> roleCodes = roles.stream().map(Role::getRoleCode).toList();

        if (roleCodes.contains("ADMIN")) return ADMIN_QUOTA;
        if (roleCodes.contains("BACKEND_DEV") || roleCodes.contains("FRONTEND_DEV") || roleCodes.contains("TESTER"))
            return DEV_QUOTA;
        return DEFAULT_QUOTA;
    }

    /** 创建默认目录结构 */
    private void createDefaultDirectories(String workspaceId, String userId) {
        long now = System.currentTimeMillis();

        for (String dirName : List.of("src", "docs", "uploads")) {
            WorkspaceFile dir = new WorkspaceFile();
            dir.setWorkspaceId(workspaceId);
            dir.setParentId(null);
            dir.setFileName(dirName);
            dir.setFileType(1);
            dir.setFilePath(dirName);
            dir.setFileSize(0L);
            dir.setVersion(1);
            dir.setStatus(1);
            dir.setCreatedBy(userId);
            dir.setCreatedAt(now);
            dir.setUpdatedAt(now);
            fileMapper.insert(dir);
        }
    }

    // ==================== 文件树 ====================

    /** 列出文件（指定父目录下，null=根目录） */
    public List<FileNodeDTO> listFiles(String parentId) {
        String workspaceId = getMyWorkspaceId();
        LambdaQueryWrapper<WorkspaceFile> wrapper = new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getWorkspaceId, workspaceId)
                .orderByAsc(WorkspaceFile::getFileType)
                .orderByAsc(WorkspaceFile::getFileName);
        if (parentId == null) {
            wrapper.isNull(WorkspaceFile::getParentId);
        } else {
            wrapper.eq(WorkspaceFile::getParentId, parentId);
        }
        return fileMapper.selectList(wrapper).stream().map(this::toFileNodeDTO).toList();
    }

    /** 创建目录 */
    @Transactional
    public FileNodeDTO createDirectory(CreateDirCommand command) {
        String workspaceId = getMyWorkspaceId();
        String parentId = command.getParentId();
        String dirName = command.getDirName();

        // 校验父目录存在且属于当前工作空间
        if (parentId != null) {
            validateFileOwnership(parentId, workspaceId);
        }
        // 校验同名
        checkNameConflict(workspaceId, parentId, dirName);

        String filePath = buildFilePath(parentId, dirName, workspaceId);

        WorkspaceFile dir = new WorkspaceFile();
        dir.setWorkspaceId(workspaceId);
        dir.setParentId(parentId);
        dir.setFileName(dirName);
        dir.setFileType(1);
        dir.setFilePath(filePath);
        dir.setFileSize(0L);
        dir.setVersion(1);
        dir.setStatus(1);
        dir.setCreatedBy(UserContext.currentUserId());
        fileMapper.insert(dir);

        return toFileNodeDTO(dir);
    }

    /** 上传文件 */
    @Transactional
    public FileNodeDTO uploadFile(MultipartFile file, String parentId) {
        String workspaceId = getMyWorkspaceId();
        validateFileName(file.getOriginalFilename());
        if (file.getSize() > MAX_FILE_SIZE) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "文件超过 100MB 限制");
        }

        Workspace ws = getMyWorkspaceEntity();
        checkQuota(ws, file.getSize());

        if (parentId != null) {
            validateFileOwnership(parentId, workspaceId);
        }

        String fileName = file.getOriginalFilename();
        String filePath = buildFilePath(parentId, fileName, workspaceId);
        checkNameConflict(workspaceId, parentId, fileName);
        String objectKey = ws.getStoragePath() + filePath;

        storageService.uploadWorkspaceFile(objectKey, file);

        WorkspaceFile wf = new WorkspaceFile();
        wf.setWorkspaceId(workspaceId);
        wf.setParentId(parentId);
        wf.setFileName(fileName);
        wf.setFileType(2);
        wf.setFilePath(filePath);
        wf.setObjectKey(objectKey);
        wf.setMimeType(file.getContentType());
        wf.setFileSize(file.getSize());
        wf.setChecksum(sha256(file.getOriginalFilename() + file.getSize()));
        wf.setVersion(1);
        wf.setStatus(1);
        wf.setCreatedBy(UserContext.currentUserId());
        fileMapper.insert(wf);

        ws.setUsedBytes(ws.getUsedBytes() + file.getSize());
        ws.setFileCount(ws.getFileCount() + 1);
        workspaceMapper.updateById(ws);

        return toFileNodeDTO(wf);
    }

    /**
     * 从文本内容创建文件（AI 生成文件专用）。
     * <p>
     * 在 "ai-generated" 目录下创建新文件，内容直接上传到 MinIO。
     * 不需要 MultipartFile，适用于 AI 推理结果直接保存为文件。
     *
     * @param fileName 文件名（如 HelloWorld.java、report.md）
     * @param content  文件文本内容
     * @param mimeType MIME 类型（如 text/plain, text/markdown, application/json）
     * @return 创建的文件节点 DTO（含 fileId）
     * @since 1.0.0
     */
    @Transactional
    public FileNodeDTO createFileFromContent(String fileName, String content, String mimeType) {
        String workspaceId = getMyWorkspaceId();
        String userId = UserContext.currentUserId();
        Workspace ws = getMyWorkspaceEntity();

        // 查找或创建 ai-generated 目录
        String aiDirId = getOrCreateAiGeneratedDir(workspaceId, userId);

        validateFileName(fileName);
        long fileSize = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        checkQuota(ws, fileSize);
        checkNameConflict(workspaceId, aiDirId, fileName);

        String filePath = buildFilePath(aiDirId, fileName, workspaceId);
        String objectKey = ws.getStoragePath() + filePath;

        // 上传内容到 MinIO
        storageService.uploadWorkspaceContent(objectKey, content, mimeType);

        // 入库
        WorkspaceFile wf = new WorkspaceFile();
        wf.setWorkspaceId(workspaceId);
        wf.setParentId(aiDirId);
        wf.setFileName(fileName);
        wf.setFileType(2);
        wf.setFilePath(filePath);
        wf.setObjectKey(objectKey);
        wf.setMimeType(mimeType);
        wf.setFileSize(fileSize);
        wf.setChecksum(sha256(fileName + fileSize));
        wf.setVersion(1);
        wf.setStatus(1);
        wf.setCreatedBy(userId);
        fileMapper.insert(wf);

        ws.setUsedBytes(ws.getUsedBytes() + fileSize);
        ws.setFileCount(ws.getFileCount() + 1);
        workspaceMapper.updateById(ws);

        log.info("AI 文件已创建: fileName={}, fileSize={}, fileId={}", fileName, fileSize, wf.getId());
        return toFileNodeDTO(wf);
    }

    /**
     * 从二进制内容创建文件（AI 生成 Word 文档等二进制文件专用）。
     *
     * @param fileName 文件名
     * @param data     文件二进制内容
     * @param mimeType MIME 类型
     * @return 创建的文件节点 DTO
     * @since 1.0.0
     */
    @Transactional
    public FileNodeDTO createFileFromBytes(String fileName, byte[] data, String mimeType) {
        String workspaceId = getMyWorkspaceId();
        String userId = UserContext.currentUserId();
        Workspace ws = getMyWorkspaceEntity();

        String aiDirId = getOrCreateAiGeneratedDir(workspaceId, userId);

        validateFileName(fileName);
        checkQuota(ws, data.length);
        checkNameConflict(workspaceId, aiDirId, fileName);

        String filePath = buildFilePath(aiDirId, fileName, workspaceId);
        String objectKey = ws.getStoragePath() + filePath;

        storageService.uploadWorkspaceBytes(objectKey, data, mimeType);

        WorkspaceFile wf = new WorkspaceFile();
        wf.setWorkspaceId(workspaceId);
        wf.setParentId(aiDirId);
        wf.setFileName(fileName);
        wf.setFileType(2);
        wf.setFilePath(filePath);
        wf.setObjectKey(objectKey);
        wf.setMimeType(mimeType);
        wf.setFileSize((long) data.length);
        wf.setChecksum(sha256(fileName + data.length));
        wf.setVersion(1);
        wf.setStatus(1);
        wf.setCreatedBy(userId);
        fileMapper.insert(wf);

        ws.setUsedBytes(ws.getUsedBytes() + data.length);
        ws.setFileCount(ws.getFileCount() + 1);
        workspaceMapper.updateById(ws);

        log.info("AI 二进制文件已创建: fileName={}, fileSize={}, fileId={}", fileName, data.length, wf.getId());
        return toFileNodeDTO(wf);
    }

    /**
     * 查找或创建 "ai-generated" 目录。
     */
    private String getOrCreateAiGeneratedDir(String workspaceId, String userId) {
        LambdaQueryWrapper<WorkspaceFile> wrapper = new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getWorkspaceId, workspaceId)
                .eq(WorkspaceFile::getFileName, "ai-generated")
                .eq(WorkspaceFile::getFileType, 1);
        WorkspaceFile existing = fileMapper.selectOne(wrapper);
        if (existing != null) {
            return existing.getId();
        }
        // 创建目录
        WorkspaceFile dir = new WorkspaceFile();
        dir.setWorkspaceId(workspaceId);
        dir.setParentId(null);
        dir.setFileName("ai-generated");
        dir.setFileType(1);
        dir.setFilePath("ai-generated");
        dir.setFileSize(0L);
        dir.setVersion(1);
        dir.setStatus(1);
        dir.setCreatedBy(userId);
        dir.setCreatedAt(System.currentTimeMillis());
        dir.setUpdatedAt(System.currentTimeMillis());
        fileMapper.insert(dir);
        return dir.getId();
    }

    /** 读取文件内容 */
    public String getFileContent(String fileId) {
        WorkspaceFile wf = getFileEntity(fileId, getMyWorkspaceId());
        if (wf.getFileType() == 1) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "不能读取目录内容");
        }
        if (wf.getObjectKey() == null) {
            return "";
        }
        return storageService.getContent(wf.getObjectKey());
    }

    /** 保存文件内容 */
    @Transactional
    public FileNodeDTO saveFileContent(String fileId, String content) {
        String workspaceId = getMyWorkspaceId();
        WorkspaceFile wf = getFileEntity(fileId, workspaceId);
        if (wf.getFileType() == 1) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "不能保存目录内容");
        }

        Workspace ws = getMyWorkspaceEntity();
        long oldSize = wf.getFileSize() != null ? wf.getFileSize() : 0;
        long newSize = content.getBytes(StandardCharsets.UTF_8).length;
        checkQuota(ws, newSize - oldSize);

        String objectKey = wf.getObjectKey() != null ? wf.getObjectKey()
                : ws.getStoragePath() + wf.getFilePath();
        storageService.uploadWorkspaceContent(objectKey, content, wf.getMimeType());

        wf.setObjectKey(objectKey);
        wf.setFileSize(newSize);
        wf.setChecksum(sha256(content));
        wf.setVersion(wf.getVersion() + 1);
        fileMapper.updateById(wf);

        ws.setUsedBytes(ws.getUsedBytes() + (newSize - oldSize));
        workspaceMapper.updateById(ws);

        return toFileNodeDTO(wf);
    }

    /** 重命名/移动文件 */
    @Transactional
    public FileNodeDTO renameFile(String fileId, RenameFileCommand command) {
        String workspaceId = getMyWorkspaceId();
        WorkspaceFile wf = getFileEntity(fileId, workspaceId);

        String newParentId = command.getParentId() != null ? command.getParentId() : wf.getParentId();
        if (command.getParentId() != null) {
            validateFileOwnership(command.getParentId(), workspaceId);
            // 防止移动到自身子目录
            if (fileId.equals(command.getParentId())) {
                throw BusinessException.of(ResultCode.PARAM_INVALID, "不能移动到自身");
            }
        }

        checkNameConflict(workspaceId, newParentId, command.getFileName());

        wf.setFileName(command.getFileName());
        wf.setParentId(newParentId);
        wf.setFilePath(buildFilePath(newParentId, command.getFileName(), workspaceId));
        fileMapper.updateById(wf);

        return toFileNodeDTO(wf);
    }

    /** 删除文件/目录 */
    @Transactional
    public void deleteFile(String fileId) {
        String workspaceId = getMyWorkspaceId();
        WorkspaceFile wf = getFileEntity(fileId, workspaceId);
        Workspace ws = getMyWorkspaceEntity();

        long[] freed = deleteFileRecursive(wf, workspaceId);

        ws.setUsedBytes(Math.max(0, ws.getUsedBytes() - freed[0]));
        ws.setFileCount(Math.max(0, ws.getFileCount() - (int) freed[1]));
        workspaceMapper.updateById(ws);
    }

    /** 递归删除文件及其子节点，返回 [释放字节数, 删除节点数] */
    private long[] deleteFileRecursive(WorkspaceFile wf, String workspaceId) {
        long freedBytes = 0;
        long nodes = 1;

        if (wf.getFileType() == 1) {
            // 目录: 递归删除子节点
            List<WorkspaceFile> children = fileMapper.selectList(
                    new LambdaQueryWrapper<WorkspaceFile>().eq(WorkspaceFile::getParentId, wf.getId()));
            for (WorkspaceFile child : children) {
                long[] childResult = deleteFileRecursive(child, workspaceId);
                freedBytes += childResult[0];
                nodes += childResult[1];
            }
        } else {
            // 文件: 删 MinIO 对象
            if (wf.getObjectKey() != null) {
                storageService.deleteObject(wf.getObjectKey());
            }
            freedBytes += wf.getFileSize() != null ? wf.getFileSize() : 0;
        }

        fileMapper.deleteById(wf.getId());
        return new long[]{freedBytes, nodes};
    }

    /** 下载文件（返回预签名 URL） */
    public String getDownloadUrl(String fileId) {
        WorkspaceFile wf = getFileEntity(fileId, getMyWorkspaceId());
        if (wf.getFileType() == 1) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "不能下载目录");
        }
        if (wf.getObjectKey() == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "文件内容为空");
        }
        return storageService.getPresignedUrl(wf.getObjectKey());
    }

    // ==================== 沙箱集成 ====================

    /** 创建挂载工作空间的沙箱 */
    @Transactional
    public SandboxDTO createWorkspaceSandbox(String template, String sandboxName) {
        Workspace ws = getMyWorkspaceEntity();

        CreateSandboxCommand command = new CreateSandboxCommand();
        command.setSandboxName(sandboxName != null ? sandboxName : "工作空间沙箱");
        command.setTemplate(template != null ? template : "shell");
        command.setSource("workspace");
        command.setWorkspaceId(ws.getId());

        SandboxDTO dto = sandboxClient.createSandbox(command);
        log.info("工作空间沙箱创建: workspaceId={}, sandboxId={}", ws.getId(), dto.getSandboxId());
        return dto;
    }

    // ==================== 辅助方法 ====================

    private String getMyWorkspaceId() {
        return getMyWorkspaceEntity().getId();
    }

    private Workspace getMyWorkspaceEntity() {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        Workspace ws = workspaceMapper.selectOne(
                new LambdaQueryWrapper<Workspace>().eq(Workspace::getUserId, userId));
        if (ws == null) {
            ws = initWorkspace(userId);
        }
        return ws;
    }

    private void checkQuota(Workspace ws, long additionalBytes) {
        if (ws.getUsedBytes() + additionalBytes > ws.getQuotaBytes()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID,
                    "存储空间不足: 已用 " + formatSize(ws.getUsedBytes())
                            + " / 配额 " + formatSize(ws.getQuotaBytes()));
        }
    }

    private void validateFileOwnership(String fileId, String workspaceId) {
        WorkspaceFile file = fileMapper.selectById(fileId);
        if (file == null || !file.getWorkspaceId().equals(workspaceId)) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "文件不存在或无权访问");
        }
    }

    private void checkNameConflict(String workspaceId, String parentId, String name) {
        LambdaQueryWrapper<WorkspaceFile> wrapper = new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getWorkspaceId, workspaceId)
                .eq(WorkspaceFile::getFileName, name);
        if (parentId == null) {
            wrapper.isNull(WorkspaceFile::getParentId);
        } else {
            wrapper.eq(WorkspaceFile::getParentId, parentId);
        }
        if (fileMapper.selectCount(wrapper) > 0) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "同名文件已存在: " + name);
        }
    }

    private String buildFilePath(String parentId, String fileName, String workspaceId) {
        if (fileName.contains("..") || fileName.startsWith("/")) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "非法文件名");
        }
        if (parentId == null) {
            return fileName;
        }
        WorkspaceFile parent = fileMapper.selectById(parentId);
        if (parent == null || !parent.getWorkspaceId().equals(workspaceId)) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "父目录不存在");
        }
        return parent.getFilePath() + "/" + fileName;
    }

    private void validateFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "文件名不能为空");
        }
        String lower = fileName.toLowerCase();
        for (String ext : BLOCKED_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                throw BusinessException.of(ResultCode.PARAM_INVALID, "不支持的文件类型: " + ext);
            }
        }
    }

    private WorkspaceFile getFileEntity(String fileId, String workspaceId) {
        WorkspaceFile wf = fileMapper.selectById(fileId);
        if (wf == null || !wf.getWorkspaceId().equals(workspaceId)) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "文件不存在或无权访问");
        }
        return wf;
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "";
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return bytes / 1024 + "KB";
        if (bytes < 1024L * 1024 * 1024) return bytes / (1024 * 1024) + "MB";
        return bytes / (1024L * 1024 * 1024) + "GB";
    }

    private WorkspaceDTO toDTO(Workspace ws) {
        int usagePercent = ws.getQuotaBytes() > 0
                ? (int) (ws.getUsedBytes() * 100 / ws.getQuotaBytes()) : 0;
        return WorkspaceDTO.builder()
                .workspaceId(ws.getId())
                .userId(ws.getUserId())
                .workspaceName(ws.getWorkspaceName())
                .quotaBytes(ws.getQuotaBytes())
                .usedBytes(ws.getUsedBytes())
                .fileCount(ws.getFileCount())
                .usagePercent(usagePercent)
                .build();
    }

    private FileNodeDTO toFileNodeDTO(WorkspaceFile wf) {
        Integer childrenCount = null;
        if (wf.getFileType() == 1) {
            Long count = fileMapper.selectCount(
                    new LambdaQueryWrapper<WorkspaceFile>().eq(WorkspaceFile::getParentId, wf.getId()));
            childrenCount = count != null ? count.intValue() : 0;
        }
        return FileNodeDTO.builder()
                .fileId(wf.getId())
                .parentId(wf.getParentId())
                .fileName(wf.getFileName())
                .fileType(wf.getFileType())
                .filePath(wf.getFilePath())
                .fileSize(wf.getFileSize())
                .mimeType(wf.getMimeType())
                .version(wf.getVersion())
                .childrenCount(childrenCount)
                .createdAt(wf.getCreatedAt())
                .updatedAt(wf.getUpdatedAt())
                .build();
    }
}
