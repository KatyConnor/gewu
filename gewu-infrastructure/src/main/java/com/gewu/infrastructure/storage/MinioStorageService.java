package com.gewu.infrastructure.storage;

import io.minio.*;
import io.minio.errors.*;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MinioStorageService {

    private final MinioClient minioClient;
    private final String bucketName;

    public MinioStorageService(
            @Value("${minio.endpoint:http://localhost:9000}") String endpoint,
            @Value("${minio.access-key:minioadmin}") String accessKey,
            @Value("${minio.secret-key:minioadmin123}") String secretKey,
            @Value("${minio.bucket:gewu-documents}") String bucketName) {
        this.bucketName = bucketName;
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        ensureBucket();
    }

    private void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("MinIO bucket created: {}", bucketName);
            }
        } catch (Exception e) {
            log.error("MinIO bucket init failed: {}", e.getMessage());
        }
    }

    public String uploadFile(MultipartFile file, String projectId, String phaseCode) {
        String objectName = String.format("%s/%s/%s_%s",
                projectId, phaseCode, UUID.randomUUID().toString().substring(0, 8),
                sanitizeFileName(file.getOriginalFilename()));
        try (InputStream is = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(is, file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
            log.info("File uploaded to MinIO: {}", objectName);
            return objectName;
        } catch (Exception e) {
            log.error("MinIO upload failed: {}", e.getMessage());
            throw new RuntimeException("文件上传失败: " + e.getMessage());
        }
    }

    public String uploadContent(String content, String projectId, String phaseCode, String fileName, String contentType) {
        String objectName = String.format("%s/%s/%s_%s",
                projectId, phaseCode, UUID.randomUUID().toString().substring(0, 8),
                sanitizeFileName(fileName));
        try (InputStream is = new ByteArrayInputStream(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            long size = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(is, size, -1)
                    .contentType(contentType)
                    .build());
            log.info("Content uploaded to MinIO: {}", objectName);
            return objectName;
        } catch (Exception e) {
            log.error("MinIO content upload failed: {}", e.getMessage());
            throw new RuntimeException("文档保存失败: " + e.getMessage());
        }
    }

    public String getContent(String objectName) {
        try (InputStream is = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucketName).object(objectName).build())) {
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("MinIO read failed: {}", e.getMessage());
            throw new RuntimeException("文档读取失败: " + e.getMessage());
        }
    }

    public String getPresignedUrl(String objectName) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucketName)
                    .object(objectName)
                    .expiry(7, TimeUnit.DAYS)
                    .build());
        } catch (Exception e) {
            log.error("MinIO presigned URL failed: {}", e.getMessage());
            return null;
        }
    }

    public void deleteObject(String objectName) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName).object(objectName).build());
        } catch (Exception e) {
            log.warn("MinIO delete failed (non-blocking): {}", e.getMessage());
        }
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null) return "document";
        return fileName.replaceAll("[^a-zA-Z0-9.\\-_\\u4e00-\\u9fa5]", "_");
    }

    // ==================== 工作空间存储方法 ====================

    /** 上传文件到用户工作空间（按完整 objectKey） */
    public String uploadWorkspaceFile(String objectKey, MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .stream(is, file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
            log.info("Workspace file uploaded: {}", objectKey);
            return objectKey;
        } catch (Exception e) {
            log.error("Workspace upload failed: {}", e.getMessage());
            throw new RuntimeException("文件上传失败: " + e.getMessage());
        }
    }

    /** 保存文本内容到工作空间（覆盖写） */
    public void uploadWorkspaceContent(String objectKey, String content, String contentType) {
        try (InputStream is = new ByteArrayInputStream(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            long size = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .stream(is, size, -1)
                    .contentType(contentType != null ? contentType : "text/plain")
                    .build());
        } catch (Exception e) {
            log.error("Workspace content save failed: {}", e.getMessage());
            throw new RuntimeException("文件内容保存失败: " + e.getMessage());
        }
    }

    /** 保存二进制内容到工作空间（如 Word 文档） */
    public void uploadWorkspaceBytes(String objectKey, byte[] data, String contentType) {
        try (InputStream is = new ByteArrayInputStream(data)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .stream(is, data.length, -1)
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .build());
        } catch (Exception e) {
            log.error("Workspace bytes save failed: {}", e.getMessage());
            throw new RuntimeException("二进制文件保存失败: " + e.getMessage());
        }
    }

    /** 递归删除工作空间路径下所有对象 */
    public void deleteWorkspacePath(String pathPrefix) {
        try {
            Iterable<io.minio.Result<io.minio.messages.Item>> objects = minioClient.listObjects(
                    ListObjectsArgs.builder().bucket(bucketName).prefix(pathPrefix).recursive(true).build());
            for (var item : objects) {
                try {
                    minioClient.removeObject(RemoveObjectArgs.builder()
                            .bucket(bucketName).object(item.get().objectName()).build());
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.warn("Workspace path delete failed: {}", pathPrefix);
        }
    }
}
