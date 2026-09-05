package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.file.dto.FileUploadResponseDTO;
import com.auraboot.framework.file.dto.FileRelationRequestDTO;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.file.support.FileNameEncodingSupport;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.plugin.extension.FileAccessor;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;

/**
 * Host-side implementation of the plugin file byte bridge.
 */
@RequiredArgsConstructor
public class FileAccessorImpl implements FileAccessor {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final FileService fileService;
    private final StorageProvider storageProvider;
    private final Long userId;

    @Override
    public InputStream open(String fileId) {
        FileEntity entity = requirePublicFile(fileId);
        if (!"success".equalsIgnoreCase(entity.getStatus())) {
            throw new IllegalArgumentException("File upload is not finalized: " + fileId);
        }
        String storageKey = firstText(entity.getFileName(), entity.getLocalPath());
        if (!StringUtils.hasText(storageKey)) {
            throw new IllegalStateException("File has no storage key: " + fileId);
        }
        return storageProvider.download(storageKey);
    }

    @Override
    public FileMetadata describe(String fileId) {
        FileEntity entity = requirePublicFile(fileId);
        long size = entity.getFileSize() == null ? 0L : entity.getFileSize();
        String ownerUserId = entity.getCreatedBy() == null ? null : entity.getCreatedBy().toString();
        return new FileMetadata(
                entity.getPid(),
                entity.getOriginalName(),
                size,
                entity.getMimeType(),
                ownerUserId,
                entity.getStatus());
    }

    @Override
    public boolean isLinkedTo(String fileId, String entityType, String entityId, String fieldName) {
        requirePublicFile(fileId);
        requireText(entityType, "entityType");
        requireText(entityId, "entityId");
        requireText(fieldName, "fieldName");
        List<FileEntity> related = fileService.getFilesByEntityAndField(entityType, entityId, fieldName);
        if (related == null || related.isEmpty()) {
            return false;
        }
        return related.stream().anyMatch(entity -> fileId.equals(entity.getPid()));
    }

    @Override
    public boolean retain(String fileId) {
        requirePublicFile(fileId);
        return fileService.lockRetention(fileId);
    }

    @Override
    public SavedFile save(String originalName, String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("file bytes must not be empty");
        }
        String safeName = safeOriginalName(originalName);
        String mimeType = StringUtils.hasText(contentType) ? contentType : DEFAULT_CONTENT_TYPE;
        FileUploadResponseDTO response = fileService.uploadFile(
                new ByteArrayMultipartFile("file", safeName, mimeType, bytes),
                userId);
        String responseName = StringUtils.hasText(response.getOriginalName()) ? response.getOriginalName() : safeName;
        long responseSize = response.getFileSize() != null ? response.getFileSize() : bytes.length;
        return new SavedFile(response.getFileId(), responseName, responseSize, response.getUrl());
    }

    @Override
    public SavedFile saveAndLink(
            String originalName,
            String contentType,
            byte[] bytes,
            String entityType,
            String entityId,
            String fieldName
    ) {
        requireText(entityType, "entityType");
        requireText(entityId, "entityId");
        requireText(fieldName, "fieldName");
        SavedFile saved = save(originalName, contentType, bytes);
        FileRelationRequestDTO relation = new FileRelationRequestDTO();
        relation.setEntityType(entityType);
        relation.setEntityId(entityId);
        relation.setFieldName(fieldName);
        relation.setFileIds(new String[]{saved.fileId()});
        if (!fileService.createFileRelation(relation, userId)) {
            throw new IllegalStateException("generated file relation was not persisted");
        }
        return saved;
    }

    private static String firstText(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first;
        }
        return StringUtils.hasText(second) ? second : null;
    }

    private FileEntity requirePublicFile(String fileId) {
        requireText(fileId, "fileId");
        FileEntity entity = fileService.getFileById(fileId);
        if (entity == null) {
            throw new IllegalArgumentException("File not found: " + fileId);
        }
        if (!fileId.equals(entity.getPid())) {
            throw new IllegalArgumentException("A stable public file pid is required: " + fileId);
        }
        if (Boolean.TRUE.equals(entity.getDeletedFlag()) || "deleted".equalsIgnoreCase(entity.getStatus())) {
            throw new IllegalArgumentException("File is not active: " + fileId);
        }
        return entity;
    }

    private static void requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static String safeOriginalName(String originalName) {
        if (!StringUtils.hasText(originalName)) {
            return "generated-file.bin";
        }
        String normalized = originalName.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        String basename = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        return FileNameEncodingSupport.normalizeOriginalFilename(basename);
    }

    private record ByteArrayMultipartFile(
            String name,
            String originalFilename,
            String contentType,
            byte[] bytes
    ) implements MultipartFile {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            Files.write(dest.toPath(), bytes);
        }
    }
}
