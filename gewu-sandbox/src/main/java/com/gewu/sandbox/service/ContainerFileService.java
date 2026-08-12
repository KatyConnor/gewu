package com.gewu.sandbox.service;

import com.github.dockerjava.api.DockerClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

/**
 * 容器文件传输服务 - 通过 Docker cp API 实现文件读写，绕过 CommandValidator.
 * <p>使用 tar 流通过 copyArchiveToContainerCmd / copyArchiveFromContainerCmd 传输，
 * 不经过 shell，无命令注入风险。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContainerFileService {

    private final DockerClient dockerClient;

    /** 容器内工作空间根路径 */
    private static final String WORKSPACE_ROOT = "/workspace";

    /**
     * 写入文本文件到容器（通过 tar 流 + Docker cp）.
     *
     * @param containerId Docker 容器 ID
     * @param remotePath  相对于 /workspace 的路径（如 src/main/App.java）
     * @param content     文件内容
     */
    public void writeTextFile(String containerId, String remotePath, String content) {
        validatePath(remotePath);
        String fileName = Paths.get(remotePath).getFileName().toString();
        String parentDir = getParentDir(remotePath);
        String remoteDir = WORKSPACE_ROOT + "/" + parentDir;

        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             TarArchiveOutputStream tar = new TarArchiveOutputStream(baos)) {
            TarArchiveEntry entry = new TarArchiveEntry(fileName);
            entry.setSize(data.length);
            entry.setMode(0644);
            tar.putArchiveEntry(entry);
            tar.write(data);
            tar.closeArchiveEntry();
            tar.finish();

            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(new ByteArrayInputStream(baos.toByteArray()))
                    .withRemotePath(remoteDir)
                    .exec();
            log.debug("文件写入容器: {}/{}", remoteDir, fileName);
        } catch (Exception e) {
            log.error("写入容器文件失败: path={}, error={}", remotePath, e.getMessage());
            throw new RuntimeException("文件写入失败: " + e.getMessage());
        }
    }

    /**
     * 从容器读取文件内容（通过 Docker cp + tar 解包）.
     *
     * @param containerId Docker 容器 ID
     * @param remotePath  相对于 /workspace 的路径
     * @return 文件内容字节
     */
    public byte[] readFile(String containerId, String remotePath) {
        validatePath(remotePath);
        String fullPath = WORKSPACE_ROOT + "/" + remotePath;
        try (InputStream is = dockerClient
                .copyArchiveFromContainerCmd(containerId, fullPath)
                .exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(is)) {
            TarArchiveEntry entry = tar.getNextTarEntry();
            if (entry != null && entry.isFile()) {
                return tar.readAllBytes();
            }
            return new byte[0];
        } catch (Exception e) {
            log.error("读取容器文件失败: path={}, error={}", remotePath, e.getMessage());
            throw new RuntimeException("文件读取失败: " + e.getMessage());
        }
    }

    /**
     * 从容器读取文本文件.
     */
    public String readTextFile(String containerId, String remotePath) {
        return new String(readFile(containerId, remotePath), StandardCharsets.UTF_8);
    }

    /**
     * 上传 MultipartFile 到容器（通过 tar 流）.
     *
     * @param containerId Docker 容器 ID
     * @param remotePath  相对于 /workspace 的目标路径（含文件名）
     * @param data        文件字节数据
     * @param fileName    文件名
     */
    public void uploadFile(String containerId, String remotePath, byte[] data, String fileName) {
        validatePath(remotePath);
        String parentDir = getParentDir(remotePath);
        String remoteDir = WORKSPACE_ROOT + "/" + parentDir;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             TarArchiveOutputStream tar = new TarArchiveOutputStream(baos)) {
            TarArchiveEntry entry = new TarArchiveEntry(fileName);
            entry.setSize(data.length);
            entry.setMode(0644);
            tar.putArchiveEntry(entry);
            tar.write(data);
            tar.closeArchiveEntry();
            tar.finish();

            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(new ByteArrayInputStream(baos.toByteArray()))
                    .withRemotePath(remoteDir)
                    .exec();
            log.debug("文件上传到容器: {}/{}", remoteDir, fileName);
        } catch (Exception e) {
            log.error("上传容器文件失败: path={}, error={}", remotePath, e.getMessage());
            throw new RuntimeException("文件上传失败: " + e.getMessage());
        }
    }

    /**
     * 从容器下载文件为字节数组.
     */
    public byte[] downloadFile(String containerId, String remotePath) {
        return readFile(containerId, remotePath);
    }

    // ==================== 辅助方法 ====================

    private void validatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        if (path.contains("..")) {
            throw new IllegalArgumentException("路径包含非法字符: ..");
        }
        if (path.startsWith("/")) {
            throw new IllegalArgumentException("请使用相对路径（相对于 /workspace）");
        }
    }

    private String getParentDir(String path) {
        var parent = Paths.get(path).getParent();
        if (parent == null) return ".";
        String dir = parent.toString().replace("\\", "/");
        return dir.isEmpty() ? "." : dir;
    }
}
