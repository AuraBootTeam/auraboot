package com.auraboot.framework.file.service.impl;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.file.constant.StorageType;
import com.auraboot.framework.file.constant.UploadStatus;
import com.auraboot.framework.file.dao.mapper.FileMapper;
import com.auraboot.framework.file.dao.mapper.FileRelationMapper;
import com.auraboot.framework.file.dto.FileRelationRequestDTO;
import com.auraboot.framework.file.dto.FileUploadResponseDTO;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.entity.FileRelationEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.infrastructure.storage.CdnUrlRewriter;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.auraboot.framework.exception.BusinessException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * File service implementation backed by {@link StorageProvider} SPI.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final FileMapper fileMapper;
    private final FileRelationMapper fileRelationMapper;
    private final StorageProvider storageProvider;

    @Autowired(required = false)
    private CdnUrlRewriter cdnUrlRewriter;

    @Value("${file.download.base-url:/222}")
    private String baseUrl;

    /** Max upload size: 50 MB */
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    /** Allowed MIME types for upload */
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml",
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.ms-excel", "application/msword",
            "text/plain", "text/csv", "text/markdown", "text/html",
            "application/json", "application/xml",
            "application/zip", "application/gzip"
    );

    /** Blocked file extensions (executables, scripts) */
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "bat", "cmd", "sh", "ps1", "vbs", "js", "mjs",
            "jsp", "php", "py", "rb", "pl", "cgi", "war", "jar", "class"
    );

    @Override
    public FileEntity findByPid(String pid) {
        QueryWrapper<FileEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(FileEntity::getPid, pid);
        return fileMapper.selectOne(queryWrapper);
    }

    @Override
    public FileUploadResponseDTO uploadFile(MultipartFile file, Long userId) {
        try {
            if (file.isEmpty()) {
                throw new IllegalArgumentException("File must not be empty");
            }

            // Validate file size
            if (file.getSize() > MAX_FILE_SIZE) {
                throw new BusinessException("File too large: max " + (MAX_FILE_SIZE / 1024 / 1024) + "MB");
            }

            // Validate MIME type
            String contentType = file.getContentType();
            if (contentType == null || !ALLOWED_MIME_TYPES.contains(contentType.toLowerCase())) {
                throw new BusinessException("File type not allowed: " + contentType);
            }

            // Validate file extension
            String extension = getFileExtension(file.getOriginalFilename());
            if (BLOCKED_EXTENSIONS.contains(extension.toLowerCase())) {
                throw new BusinessException("File extension not allowed: " + extension);
            }

            FileEntity fileEntity = createFileEntity(file, userId);
            fileEntity.setPid(UniqueIdGenerator.generate());

            // Delegate to StorageProvider
            String storageKey = fileEntity.getFileName();
            String storedPath = storageProvider.upload(
                    storageKey,
                    file.getInputStream(),
                    file.getSize(),
                    file.getContentType());

            fileEntity.setLocalPath(storedPath);
            fileEntity.setStorageType(storageProvider.type());
            fileEntity.setStatus(UploadStatus.SUCCESS.getCode());

            fileMapper.insert(fileEntity);
            return buildUploadResponse(fileEntity);

        } catch (Exception e) {
            log.error("File upload failed", e);
            throw new BusinessException("File upload failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<FileUploadResponseDTO> uploadFiles(MultipartFile[] files, Long userId) {
        List<FileUploadResponseDTO> responses = new ArrayList<>();
        for (MultipartFile file : files) {
            responses.add(uploadFile(file, userId));
        }
        return responses;
    }

    @Override
    public FileEntity getFileById(String fileId) {
        return fileMapper.selectById(fileId);
    }

    @Override
    public List<FileEntity> getFilesByUserId(Long userId) {
        return fileMapper.selectByCreatedBy(userId);
    }

    @Override
    @Transactional
    public boolean deleteFile(String fileId, Long userId) {
        FileEntity fileEntity = fileMapper.selectById(fileId);
        if (fileEntity == null) {
            throw new BusinessException("File not found: " + fileId);
        }
        if (!fileEntity.getCreatedBy().equals(userId)) {
            throw new BusinessException("You don't have permission to delete this file");
        }

        // Delete physical file from storage
        try {
            storageProvider.delete(fileEntity.getFileName());
        } catch (Exception e) {
            log.warn("Failed to delete physical file: key={}, error={}", fileEntity.getFileName(), e.getMessage());
        }

        fileEntity.setStatus(UploadStatus.DELETED.getCode());
        fileMapper.updateById(fileEntity);
        return fileMapper.deleteById(fileEntity.getId()) > 0;
    }

    @Override
    @Transactional
    public boolean deleteFiles(String[] fileIds, Long userId) {
        boolean allSuccess = true;
        for (String fileId : fileIds) {
            if (!deleteFile(fileId, userId)) {
                allSuccess = false;
            }
        }
        return allSuccess;
    }

    @Override
    @Transactional
    public boolean createFileRelation(FileRelationRequestDTO request) {
        removeFileRelation(request.getEntityType(), request.getEntityId(), request.getFieldName());

        for (int i = 0; i < request.getFileIds().length; i++) {
            FileRelationEntity relation = new FileRelationEntity();
            relation.setFileId(request.getFileIds()[i]);
            relation.setEntityType(request.getEntityType());
            relation.setEntityId(request.getEntityId());
            relation.setFieldName(request.getFieldName());
            relation.setSortOrder(i);
            fileRelationMapper.insert(relation);
        }
        return true;
    }

    @Override
    public List<FileEntity> getFilesByEntity(String entityType, String entityId) {
        List<String> fileIds = fileRelationMapper.findFileIdsByEntity(entityType, entityId);
        if (fileIds.isEmpty()) {
            return new ArrayList<>();
        }

        QueryWrapper<FileEntity> wrapper = new QueryWrapper<>();
        wrapper.in("id", fileIds);
        wrapper.eq("deleted_flag", false);
        return fileMapper.selectList(wrapper);
    }

    @Override
    public List<FileEntity> getFilesByEntityAndField(String entityType, String entityId, String fieldName) {
        List<String> fileIds = fileRelationMapper.findFileIdsByEntityAndField(entityType, entityId, fieldName);
        if (fileIds.isEmpty()) {
            return new ArrayList<>();
        }

        QueryWrapper<FileEntity> wrapper = new QueryWrapper<>();
        wrapper.in("id", fileIds);
        wrapper.eq("deleted_flag", false);
        return fileMapper.selectList(wrapper);
    }

    @Override
    @Transactional
    public boolean removeFileRelation(String entityType, String entityId, String fieldName) {
        QueryWrapper<FileRelationEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("entity_type", entityType)
               .eq("entity_id", entityId)
               .eq("field_name", fieldName);
        return fileRelationMapper.delete(wrapper) >= 0;
    }

    @Override
    public String getFileDownloadUrl(String fileId) {
        // fileId may be a pid (ULID string) — try findByPid first, fallback to selectById
        FileEntity fileEntity = findByPid(fileId);
        if (fileEntity == null) {
            return null;
        }

        String storageKey = fileEntity.getFileName();

        // CDN takes priority
        if (cdnUrlRewriter != null) {
            return cdnUrlRewriter.rewrite(storageKey);
        }

        if (fileEntity.getStorageType() == StorageType.LOCAL) {
            return baseUrl + "/api/file/download/" + fileId;
        }

        // For cloud providers, try pre-signed URL
        try {
            return storageProvider.getPresignedUrl(storageKey, Duration.ofHours(1));
        } catch (UnsupportedOperationException e) {
            return fileEntity.getCloudPath();
        }
    }

    private FileEntity createFileEntity(MultipartFile file, Long userId) {
        String originalFilename = file.getOriginalFilename();
        String extension = getFileExtension(originalFilename);
        String filename = UniqueIdGenerator.generate() + "." + extension;

        FileEntity fileEntity = new FileEntity();
        fileEntity.setFileName(filename);
        fileEntity.setOriginalName(originalFilename);
        fileEntity.setFileSize(file.getSize());
        fileEntity.setMimeType(file.getContentType());
        fileEntity.setFileExtension(extension);
        fileEntity.setStorageType(storageProvider.type());
        fileEntity.setStatus(UploadStatus.UPLOADING.getCode());
        fileEntity.setCreatedBy(userId);
        fileEntity.setCreatedTime(Instant.now());
        fileEntity.setUpdatedTime(Instant.now());
        fileEntity.setDeletedFlag(false);

        return fileEntity;
    }

    private FileUploadResponseDTO buildUploadResponse(FileEntity fileEntity) {
        FileUploadResponseDTO response = new FileUploadResponseDTO();
        response.setFileId(fileEntity.getPid());
        response.setOriginalName(fileEntity.getOriginalName());
        response.setFileSize(fileEntity.getFileSize());
        response.setUrl(getFileDownloadUrl(fileEntity.getPid()));
        response.setStorageType(fileEntity.getStorageType());
        response.setStatus(fileEntity.getStatus());
        response.setUploadTime(com.auraboot.framework.common.util.DateUtil.toUtcLocalDateTime(fileEntity.getCreatedTime()));
        return response;
    }

    private String getFileExtension(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        int lastDotIndex = filename.lastIndexOf('.');
        return lastDotIndex > 0 ? filename.substring(lastDotIndex + 1) : "";
    }
}
