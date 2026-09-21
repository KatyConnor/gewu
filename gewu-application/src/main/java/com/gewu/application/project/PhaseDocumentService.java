package com.gewu.application.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.project.dto.*;
import com.gewu.common.context.UserContext;
import com.gewu.common.enums.ReviewStatus;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.project.PhaseDocument;
import com.gewu.domain.project.PhaseDocumentVersion;
import com.gewu.infrastructure.mapper.PhaseDocumentMapper;
import com.gewu.infrastructure.mapper.PhaseDocumentVersionMapper;
import com.gewu.infrastructure.storage.MinioStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PhaseDocumentService {

    private final PhaseDocumentMapper documentMapper;
    private final PhaseDocumentVersionMapper versionMapper;
    private final MinioStorageService storageService;

    public List<PhaseDocumentDTO> listDocuments(String projectId, String phaseCode) {
        return documentMapper.selectList(
                new LambdaQueryWrapper<PhaseDocument>()
                        .eq(PhaseDocument::getProjectId, projectId)
                        .eq(PhaseDocument::getPhaseCode, phaseCode))
                .stream().map(this::toDTO).toList();
    }

    public PhaseDocumentDTO getDocument(String documentId) {
        return toDTO(getOrThrow(documentId));
    }

    public String getDocumentContent(String documentId) {
        PhaseDocument doc = getOrThrow(documentId);
        PhaseDocumentVersion latest = getLatestVersionEntity(documentId);
        if (latest == null) return "";
        return storageService.getContent(latest.getFileUrl());
    }

    @Transactional
    public PhaseDocumentDTO uploadFile(String projectId, String phaseCode, MultipartFile file) {
        String objectName = storageService.uploadFile(file, projectId, phaseCode);
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";
        String docType = getDocType(fileName);

        PhaseDocument doc = new PhaseDocument();
        doc.setProjectId(projectId);
        doc.setPhaseCode(phaseCode);
        doc.setDocName(fileName);
        doc.setDocType(docType);
        doc.setCurrentVersion(1);
        doc.setTotalVersions(1);
        doc.setReviewStatus(ReviewStatus.DRAFT.getCode());
        documentMapper.insert(doc);

        PhaseDocumentVersion version = new PhaseDocumentVersion();
        version.setDocumentId(doc.getId());
        version.setVersionNo(1);
        version.setFileUrl(objectName);
        version.setFileSize(file.getSize());
        version.setChangeSource("manual");
        version.setUploadedBy(UserContext.currentUserId());
        version.setUploadedAt(System.currentTimeMillis());
        versionMapper.insert(version);

        return toDTO(doc);
    }

    @Transactional
    public PhaseDocumentDTO createMarkdownDoc(String projectId, String phaseCode, CreateDocumentCommand command) {
        String objectName = storageService.uploadContent(command.getContent(), projectId, phaseCode,
                command.getDocName(), "text/markdown");

        PhaseDocument doc = new PhaseDocument();
        doc.setProjectId(projectId);
        doc.setPhaseCode(phaseCode);
        doc.setDocName(command.getDocName());
        doc.setDocType("md");
        doc.setCurrentVersion(1);
        doc.setTotalVersions(1);
        doc.setReviewStatus(ReviewStatus.DRAFT.getCode());
        documentMapper.insert(doc);

        PhaseDocumentVersion version = new PhaseDocumentVersion();
        version.setDocumentId(doc.getId());
        version.setVersionNo(1);
        version.setFileUrl(objectName);
        version.setContentMd5(md5(command.getContent()));
        version.setFileSize((long) command.getContent().getBytes(StandardCharsets.UTF_8).length);
        version.setChangeSource("manual");
        version.setUploadedBy(UserContext.currentUserId());
        version.setUploadedAt(System.currentTimeMillis());
        versionMapper.insert(version);

        return toDTO(doc);
    }

    @Transactional
    public PhaseDocumentDTO updateContent(String documentId, UpdateDocumentContentCommand command) {
        PhaseDocument doc = getOrThrow(documentId);
        String status = doc.getReviewStatus();
        if (ReviewStatus.IN_REVIEW.getCode().equals(status) || ReviewStatus.APPROVED.getCode().equals(status)) {
            throw BusinessException.of(ResultCode.DOCUMENT_LOCKED);
        }

        String objectName = storageService.uploadContent(command.getContent(), doc.getProjectId(), doc.getPhaseCode(),
                doc.getDocName(), "text/markdown");
        int nextVersion = doc.getCurrentVersion() + 1;

        PhaseDocumentVersion version = new PhaseDocumentVersion();
        version.setDocumentId(doc.getId());
        version.setVersionNo(nextVersion);
        version.setFileUrl(objectName);
        version.setContentMd5(md5(command.getContent()));
        version.setChangeSummary(command.getChangeSummary());
        version.setChangeSource("manual");
        version.setFileSize((long) command.getContent().getBytes(StandardCharsets.UTF_8).length);
        version.setUploadedBy(UserContext.currentUserId());
        version.setUploadedAt(System.currentTimeMillis());
        versionMapper.insert(version);

        doc.setCurrentVersion(nextVersion);
        doc.setTotalVersions(nextVersion);
        doc.setReviewStatus(ReviewStatus.PENDING.getCode());
        documentMapper.updateById(doc);

        return toDTO(doc);
    }

    @Transactional
    public void deleteDocument(String documentId) {
        PhaseDocument doc = getOrThrow(documentId);
        // 级联清理各版本对应的 MinIO 对象（file_url 即 objectName）
        List<PhaseDocumentVersion> versions = versionMapper.selectList(
                new LambdaQueryWrapper<PhaseDocumentVersion>()
                        .eq(PhaseDocumentVersion::getDocumentId, documentId));
        for (PhaseDocumentVersion version : versions) {
            if (version.getFileUrl() != null && !version.getFileUrl().isBlank()) {
                storageService.deleteObject(version.getFileUrl());
            }
        }
        versionMapper.delete(new LambdaQueryWrapper<PhaseDocumentVersion>()
                .eq(PhaseDocumentVersion::getDocumentId, documentId));
        documentMapper.deleteById(documentId);
    }

    @Transactional
    public void submitForReview(String documentId) {
        PhaseDocument doc = getOrThrow(documentId);
        if (!ReviewStatus.PENDING.getCode().equals(doc.getReviewStatus())) {
            throw BusinessException.of(ResultCode.PHASE_LOCKED, "只有待审核状态的文档可以提交审核");
        }
        doc.setReviewStatus(ReviewStatus.IN_REVIEW.getCode());
        documentMapper.updateById(doc);
    }

    @Transactional
    public void approveReview(String documentId, String agentId, String comment) {
        PhaseDocument doc = getOrThrow(documentId);
        doc.setReviewStatus(ReviewStatus.APPROVED.getCode());
        doc.setReviewedBy(agentId);
        doc.setReviewedAt(System.currentTimeMillis());
        doc.setReviewComment(comment);
        documentMapper.updateById(doc);
    }

    @Transactional
    public void rejectReview(String documentId, String agentId, String comment) {
        PhaseDocument doc = getOrThrow(documentId);
        doc.setReviewStatus(ReviewStatus.REJECTED.getCode());
        doc.setReviewedBy(agentId);
        doc.setReviewedAt(System.currentTimeMillis());
        doc.setReviewComment(comment);
        documentMapper.updateById(doc);
    }

    public List<DocumentVersionDTO> getVersions(String documentId) {
        return versionMapper.selectList(
                new LambdaQueryWrapper<PhaseDocumentVersion>()
                        .eq(PhaseDocumentVersion::getDocumentId, documentId)
                        .orderByDesc(PhaseDocumentVersion::getVersionNo))
                .stream().map(v -> DocumentVersionDTO.builder()
                        .id(v.getId())
                        .documentId(v.getDocumentId())
                        .versionNo(v.getVersionNo())
                        .fileUrl(v.getFileUrl())
                        .contentMd5(v.getContentMd5())
                        .changeSummary(v.getChangeSummary())
                        .changeSource(v.getChangeSource())
                        .agentId(v.getAgentId())
                        .agentPrompt(v.getAgentPrompt())
                        .fileSize(v.getFileSize())
                        .uploadedBy(v.getUploadedBy())
                        .uploadedAt(v.getUploadedAt())
                        .build()).toList();
    }

    public String getVersionContent(String documentId, int versionNo) {
        PhaseDocumentVersion version = getVersionOrThrow(documentId, versionNo);
        return storageService.getContent(version.getFileUrl());
    }

    public DocumentVersionDTO getLatestVersionDTO(String documentId) {
        PhaseDocumentVersion v = getLatestVersionEntity(documentId);
        if (v == null) return null;
        return toVersionDTO(v);
    }

    private DocumentVersionDTO toVersionDTO(PhaseDocumentVersion v) {
        return DocumentVersionDTO.builder()
                .id(v.getId()).documentId(v.getDocumentId())
                .versionNo(v.getVersionNo()).fileUrl(v.getFileUrl())
                .contentMd5(v.getContentMd5()).changeSummary(v.getChangeSummary())
                .changeSource(v.getChangeSource()).agentId(v.getAgentId())
                .agentPrompt(v.getAgentPrompt()).fileSize(v.getFileSize())
                .uploadedBy(v.getUploadedBy()).uploadedAt(v.getUploadedAt()).build();
    }

    private PhaseDocumentVersion getLatestVersionEntity(String documentId) {
        List<PhaseDocumentVersion> versions = versionMapper.selectList(
                new LambdaQueryWrapper<PhaseDocumentVersion>()
                        .eq(PhaseDocumentVersion::getDocumentId, documentId)
                        .orderByDesc(PhaseDocumentVersion::getVersionNo));
        return versions.isEmpty() ? null : versions.get(0);
    }

    private PhaseDocument getOrThrow(String id) {
        PhaseDocument doc = documentMapper.selectById(id);
        if (doc == null) throw BusinessException.of(ResultCode.DOCUMENT_NOT_FOUND);
        return doc;
    }

    private PhaseDocumentVersion getVersionOrThrow(String documentId, int versionNo) {
        PhaseDocumentVersion version = versionMapper.selectOne(
                new LambdaQueryWrapper<PhaseDocumentVersion>()
                        .eq(PhaseDocumentVersion::getDocumentId, documentId)
                        .eq(PhaseDocumentVersion::getVersionNo, versionNo));
        if (version == null) throw BusinessException.of(ResultCode.NOT_FOUND);
        return version;
    }

    private PhaseDocumentDTO toDTO(PhaseDocument doc) {
        String statusDesc = "";
        try {
            statusDesc = ReviewStatus.valueOf(doc.getReviewStatus().toUpperCase()).getDescription();
        } catch (Exception ignored) {}

        return PhaseDocumentDTO.builder()
                .id(doc.getId()).projectId(doc.getProjectId())
                .phaseCode(doc.getPhaseCode()).docName(doc.getDocName())
                .docType(doc.getDocType()).currentVersion(doc.getCurrentVersion())
                .totalVersions(doc.getTotalVersions())
                .reviewStatus(doc.getReviewStatus()).reviewStatusDesc(statusDesc)
                .reviewedBy(doc.getReviewedBy()).reviewedAt(doc.getReviewedAt())
                .reviewComment(doc.getReviewComment())
                .createdAt(doc.getCreatedAt()).createdBy(doc.getCreatedBy()).build();
    }

    private String getDocType(String fileName) {
        if (fileName == null) return "bin";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md")) return "md";
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".docx")) return "docx";
        if (lower.endsWith(".doc")) return "doc";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif")) return "image";
        return "bin";
    }

    private String md5(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }
}
