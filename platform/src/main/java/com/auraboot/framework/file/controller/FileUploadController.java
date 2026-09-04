package com.auraboot.framework.file.controller;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.audit.entity.AdminEventLog;
import com.auraboot.framework.audit.service.AdminEventLogService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.file.constant.StorageType;
import com.auraboot.framework.file.dto.FileInfoRequestDTO;
import com.auraboot.framework.file.dto.FileRelationRequestDTO;
import com.auraboot.framework.file.dto.FileUploadResponseDTO;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.entity.FileRelationEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.file.support.FileNameEncodingSupport;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.meta.service.DataAccessAuthorizationHelper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;

/**
 * 文件上传控制器
 */
@RestController
@RequestMapping("/api/file")
@Tag(name = "Files", description = "File upload and management")
public class FileUploadController {
    private static final Logger LOG = LoggerFactory.getLogger(FileUploadController.class);

    @Autowired
    private FileService fileService;
    @Autowired
    private StorageProvider storageProvider;
    @Autowired
    private DynamicDataService dynamicDataService;
    @Autowired
    private DataAccessAuthorizationHelper dataAccessAuthorizationHelper;
    @Autowired
    private AdminEventLogService adminEventLogService;
    
    /**
     * Single file upload via multipart
     */
    @PostMapping("/upload")
    @RequirePermission(MetaPermission.SYS_FILE_UPLOAD)
    public ApiResponse<FileUploadResponseDTO> uploadFile(
            @RequestParam("file") MultipartFile file,
            @CurrentUserId Long userId) {
        FileUploadResponseDTO response = fileService.uploadFile(file, userId);
        return ApiResponse.success(response);
    }

    /**
     * Multiple file upload via multipart
     */
    @PostMapping("/upload/batch")
    @RequirePermission(MetaPermission.SYS_FILE_UPLOAD)
    public ApiResponse<List<FileUploadResponseDTO>> uploadFiles(
            @RequestParam("files") MultipartFile[] files,
            @CurrentUserId Long userId) {
        List<FileUploadResponseDTO> responses = fileService.uploadFiles(files, userId);
        return ApiResponse.success(responses);
    }


    @PostMapping("/create")
    @RequirePermission(MetaPermission.SYS_FILE_UPLOAD)
    @ResponseBody
    public ApiResponse< FileUploadResponseDTO> create(
            @RequestBody   FileInfoRequestDTO fileInfoRequestDTO,
            @CurrentUserId Long userId) {
        // A client-supplied storage key is not proof that the caller uploaded or owns the
        // underlying object. Registering metadata for an existing key therefore creates an
        // object-read alias, even inside one tenant. Until a server-issued upload-session
        // capability exists, only the multipart endpoint may create durable file metadata.
        throw new BusinessException(
                "Metadata-only file registration is disabled; use /api/file/upload");
    }
    
    /**
     * 获取文件信息
     */
    @GetMapping("/{fileId}")
    @RequirePermission(MetaPermission.SYS_FILE_READ)
    public ApiResponse<FileUploadResponseDTO> getFile(
            @PathVariable String fileId,
            @CurrentUserId Long userId) {
        FileEntity fileEntity = fileService.getFileById(fileId);
        authorizeFileAccess(fileId, fileEntity, userId);
        return ApiResponse.success(toDto(fileEntity));
    }

    /**
     * 获取用户文件列表
     */
    @GetMapping("/list")
    @RequirePermission(MetaPermission.SYS_FILE_READ)
    public ApiResponse<List<FileUploadResponseDTO>> getUserFiles(@CurrentUserId Long userId) {
        List<FileEntity> files = fileService.getFilesByUserId(userId);
        return ApiResponse.success(files.stream().map(this::toDto).toList());
    }
    
    /**
     * 删除文件
     */
    @DeleteMapping("/{fileId}")
    @RequirePermission(MetaPermission.SYS_FILE_DELETE)
    public ApiResponse<Boolean> deleteFile(
            @PathVariable String fileId,
            @CurrentUserId Long userId) {
        boolean success = fileService.deleteFile(fileId, userId);
        return ApiResponse.success(success);
    }
    
    /**
     * 批量删除文件
     */
    @DeleteMapping("/batch")
    @RequirePermission(MetaPermission.SYS_FILE_DELETE)
    public ApiResponse<Boolean> deleteFiles(
            @RequestBody String[] fileIds,
            @CurrentUserId Long userId) {
        boolean success = fileService.deleteFiles(fileIds, userId);
        return ApiResponse.success(success);
    }
    
    /**
     * 建立文件关联
     */
    @PostMapping("/relation")
    @RequirePermission(MetaPermission.SYS_FILE_RELATION_MANAGE)
    public ApiResponse<Boolean> createFileRelation(
            @RequestBody FileRelationRequestDTO request,
            @CurrentUserId Long userId) {
        requireRelationRequest(request);
        authorizeRelationTarget(request.getEntityType(), request.getEntityId(), "update");
        boolean success = fileService.createFileRelation(request, userId);
        return ApiResponse.success(success);
    }
    
    /**
     * 获取实体关联的文件
     */
    @GetMapping("/relation/{entityType}/{entityId}")
    @RequirePermission(MetaPermission.SYS_FILE_READ)
    public ApiResponse<List<FileUploadResponseDTO>> getEntityFiles(
            @PathVariable String entityType,
            @PathVariable String entityId) {
        authorizeRelationTarget(entityType, entityId, "read");
        List<FileEntity> files = fileService.getFilesByEntity(entityType, entityId);
        return ApiResponse.success(files.stream().map(this::toDto).toList());
    }

    /**
     * 获取实体指定字段关联的文件
     */
    @GetMapping("/relation/{entityType}/{entityId}/{fieldName}")
    @RequirePermission(MetaPermission.SYS_FILE_READ)
    public ApiResponse<List<FileUploadResponseDTO>> getEntityFieldFiles(
            @PathVariable String entityType,
            @PathVariable String entityId,
            @PathVariable String fieldName) {
        authorizeRelationTarget(entityType, entityId, "read");
        List<FileEntity> files = fileService.getFilesByEntityAndField(entityType, entityId, fieldName);
        return ApiResponse.success(files.stream().map(this::toDto).toList());
    }
    
    /**
     * File download — delegates to StorageProvider which validates path traversal.
     * TenantLineInterceptor ensures the file belongs to the current tenant.
     */
    @GetMapping("/download/{fileId}")
    @RequirePermission(MetaPermission.SYS_FILE_READ)
    public ResponseEntity<Resource> downloadFile(
            @PathVariable String fileId,
            @CurrentUserId Long userId) {
        FileEntity fileEntity = fileService.getFileById(fileId);
        if (fileEntity == null) {
            return ResponseEntity.notFound().build();
        }
        authorizeFileAccess(fileId, fileEntity, userId);

        // Determine the storage key: localPath for LOCAL, cloudKey for cloud providers
        String storageKey = fileEntity.getLocalPath();
        if (storageKey == null || storageKey.isBlank()) {
            storageKey = fileEntity.getCloudKey();
        }
        if (storageKey == null || storageKey.isBlank()) {
            return ResponseEntity.notFound().build();
        }

        try {
            // StorageProvider.download() validates path traversal for local storage
            InputStream stream = storageProvider.download(storageKey);
            Resource resource = new InputStreamResource(stream);
            MediaType contentType = resolveDownloadContentType(fileEntity);
            String dispositionType = isInlineDisplayType(contentType) ? "inline" : "attachment";
            adminEventLogService.record(AdminEventLog.builder()
                    .actorUserId(userId)
                    .actionType("file.download")
                    .resourceType("file")
                    .resourcePid(fileId)
                    .success(true)
                    .reason("authorized file download")
                    .build());
            return ResponseEntity.ok()
                    .contentType(contentType)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            FileNameEncodingSupport.contentDisposition(dispositionType, downloadFileName(fileEntity, fileId)))
                    .body(resource);
        } catch (SecurityException e) {
            LOG.warn("Path traversal attempt blocked for file {}: {}", fileId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    private MediaType resolveDownloadContentType(FileEntity fileEntity) {
        String mimeType = fileEntity.getMimeType();
        if (StringUtils.hasText(mimeType)) {
            try {
                return MediaType.parseMediaType(mimeType);
            } catch (InvalidMediaTypeException e) {
                LOG.debug("Invalid stored MIME type for file {}: {}", fileEntity.getPid(), mimeType);
            }
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private boolean isInlineDisplayType(MediaType contentType) {
        return "image".equalsIgnoreCase(contentType.getType());
    }

    private String downloadFileName(FileEntity fileEntity, String fileId) {
        if (StringUtils.hasText(fileEntity.getOriginalName())) {
            return fileEntity.getOriginalName();
        }
        if (StringUtils.hasText(fileEntity.getFileName())) {
            return fileEntity.getFileName();
        }
        return fileId;
    }

    private void authorizeRelationTarget(String entityType, String entityId, String action) {
        dataAccessAuthorizationHelper.authorizeRecordId(
                entityType,
                action,
                entityId,
                recordPid -> dynamicDataService.getById(entityType, recordPid));
    }

    private void authorizeFileAccess(String fileId, FileEntity file, Long userId) {
        if (file == null) {
            return;
        }
        List<FileRelationEntity> relations = fileService.getFileRelations(fileId);
        if (relations.isEmpty()) {
            if (userId == null || file.getCreatedBy() == null || !file.getCreatedBy().equals(userId)) {
                throw new AccessDeniedException("File is not accessible to the current user");
            }
            return;
        }
        for (FileRelationEntity relation : relations) {
            authorizeRelationTarget(relation.getEntityType(), relation.getEntityId(), "read");
        }
    }

    private static void requireRelationRequest(FileRelationRequestDTO request) {
        if (request == null || !StringUtils.hasText(request.getEntityType())
                || !StringUtils.hasText(request.getEntityId())
                || !StringUtils.hasText(request.getFieldName())
                || request.getFileIds() == null) {
            throw new IllegalArgumentException(
                    "entityType, entityId, fieldName and fileIds are required");
        }
    }

/**
 * 构建文件上传响应
 */
private FileUploadResponseDTO buildUploadResponse(FileEntity fileEntity) {
    FileUploadResponseDTO response = new FileUploadResponseDTO();
    response.setFileId(fileEntity.getPid());
    response.setFileName(fileEntity.getFileName());
    response.setOriginalName(fileEntity.getOriginalName());
    response.setFileSize(fileEntity.getFileSize());
    response.setMimeType(fileEntity.getMimeType());
    // Security: never expose server-side storage paths to clients
    response.setCloudPath(fileEntity.getCloudPath());
    response.setStorageType(fileEntity.getStorageType());
    response.setStatus(fileEntity.getStatus());
    response.setUploadTime(com.auraboot.framework.common.util.DateUtil.toUtcLocalDateTime(fileEntity.getCreatedTime()));
    
    // 生成下载URL
    String downloadUrl = getFileDownloadUrl(fileEntity);
    response.setUrl(downloadUrl);
    
    return response;
}

private FileUploadResponseDTO toDto(FileEntity fileEntity) {
    return buildUploadResponse(fileEntity);
}

/**
 * 获取文件下载URL
 */
private String getFileDownloadUrl(FileEntity fileEntity) {
    if (fileEntity.getStorageType() == StorageType.LOCAL) {
        return "/download/" + fileEntity.getId();
    } else {
        return fileEntity.getCloudPath();
    }
}
}
