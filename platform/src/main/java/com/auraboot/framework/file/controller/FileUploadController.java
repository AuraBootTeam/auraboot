package com.auraboot.framework.file.controller;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.file.constant.StorageType;
import com.auraboot.framework.file.dao.mapper.FileMapper;
import com.auraboot.framework.file.dto.FileInfoRequestDTO;
import com.auraboot.framework.file.dto.FileRelationRequestDTO;
import com.auraboot.framework.file.dto.FileUploadResponseDTO;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.file.support.FileNameEncodingSupport;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
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
import org.springframework.util.StringUtils;
import com.auraboot.framework.application.tenant.MetaContext;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * 文件上传控制器
 */
@RestController
@RequestMapping("/api/file")
@RequirePermission(MetaPermission.SYS_FILE_UPLOAD)
@Tag(name = "Files", description = "File upload and management")
public class FileUploadController {
    private static final Logger LOG = LoggerFactory.getLogger(FileUploadController.class);

    @Autowired
    private FileMapper fileMapper;
    @Autowired
    private FileService fileService;
    @Autowired
    private StorageProvider storageProvider;
    
    /**
     * Single file upload via multipart
     */
    @PostMapping("/upload")
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
    public ApiResponse<List<FileUploadResponseDTO>> uploadFiles(
            @RequestParam("files") MultipartFile[] files,
            @CurrentUserId Long userId) {
        List<FileUploadResponseDTO> responses = fileService.uploadFiles(files, userId);
        return ApiResponse.success(responses);
    }


    @PostMapping("/create")
    @ResponseBody
    public ApiResponse< FileUploadResponseDTO> create(
            @RequestBody   FileInfoRequestDTO fileInfoRequestDTO,
            @CurrentUserId Long userId) {


            
            FileUploadResponseDTO response = processFileInfo(fileInfoRequestDTO, userId);

            
            return ApiResponse.success(response);
            

    }
    
    /**
     * 处理单个文件信息
     */
    private FileUploadResponseDTO processFileInfo(FileInfoRequestDTO fileInfo, Long userPid) {
        // 创建文件实体
        FileEntity fileEntity = new FileEntity();
        fileEntity.setFileName(fileInfo.getFileName());
        fileEntity.setOriginalName(FileNameEncodingSupport.normalizeOriginalFilename(fileInfo.getOriginalName()));
        fileEntity.setFileSize(fileInfo.getFileSize());
        fileEntity.setMimeType(fileInfo.getMimeType());
        // Security: ignore client-provided localPath to prevent arbitrary file read via download endpoint
        // localPath should only be set by server-side storage providers
        fileEntity.setCloudPath(fileInfo.getCloudPath());
        
        // 设置存储类型
        StorageType storageType = parseStorageType(fileInfo.getStorageType());
        fileEntity.setStorageType(storageType);
        
        // 从文件名提取扩展名
        String fileName = fileEntity.getFileName();
        if (fileName != null && fileName.contains(".")) {
            String extension = fileName.substring(fileName.lastIndexOf(".") + 1);
            fileEntity.setFileExtension(extension);
        }
        
        // 设置时间
        LocalDateTime uploadTime = fileInfo.getUploadTime();
        Instant uploadInstant = uploadTime != null
            ? com.auraboot.framework.common.util.DateUtil.toUtcInstant(uploadTime)
            : Instant.now();
        fileEntity.setUploadTime(uploadInstant);
        fileEntity.setCreatedTime(uploadInstant);
        fileEntity.setUpdatedTime(uploadInstant);
        
        // 设置其他字段
        fileEntity.setStatus(StatusConstants.ACTIVE);
        fileEntity.setCreatedBy(userPid);
        fileEntity.setDeletedFlag(false);
        
        // Object-level tenant isolation: file_name is the storage key that object-read
        // sinks (FileAccessorImpl / FileImageBridge) download by. Reject registering a
        // key that already belongs to a different tenant — otherwise a client could
        // claim another tenant's object key via this metadata-only endpoint and then
        // read its content cross-tenant (single shared bucket, keys carry no tenant prefix).
        String storageKey = fileEntity.getFileName();
        if (StringUtils.hasText(storageKey)) {
            Long tenantId = MetaContext.getCurrentTenantId();
            if (tenantId != null
                    && fileMapper.countByFileNameInOtherTenants(storageKey, tenantId) > 0) {
                throw new IllegalArgumentException(
                        "Storage key is already registered by another tenant");
            }
        }

        // 保存到数据库
        fileMapper.insert(fileEntity);
        
        // 构建响应
        return buildUploadResponse(fileEntity);
    }
    
    /**
     * 解析存储类型
     */
    private StorageType parseStorageType(String storageTypeStr) {
        if (storageTypeStr == null) return StorageType.LOCAL;
        switch (storageTypeStr.toLowerCase()) {
            case "local":
                return StorageType.LOCAL;
            case "oss":
                return StorageType.OSS;
            case "s3":
                return StorageType.S3;
            default:
                return StorageType.LOCAL;
        }
    }
    
    /**
     * 获取文件信息
     */
    @GetMapping("/{fileId}")
    public ApiResponse<FileUploadResponseDTO> getFile(@PathVariable String fileId) {
        FileEntity fileEntity = fileService.getFileById(fileId);
        return ApiResponse.success(toDto(fileEntity));
    }

    /**
     * 获取用户文件列表
     */
    @GetMapping("/list")
    public ApiResponse<List<FileUploadResponseDTO>> getUserFiles(@CurrentUserId Long userId) {
        List<FileEntity> files = fileService.getFilesByUserId(userId);
        return ApiResponse.success(files.stream().map(this::toDto).toList());
    }
    
    /**
     * 删除文件
     */
    @DeleteMapping("/{fileId}")
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
    public ApiResponse<Boolean> createFileRelation(@RequestBody FileRelationRequestDTO request) {
        boolean success = fileService.createFileRelation(request);
        return ApiResponse.success(success);
    }
    
    /**
     * 获取实体关联的文件
     */
    @GetMapping("/relation/{entityType}/{entityId}")
    public ApiResponse<List<FileUploadResponseDTO>> getEntityFiles(
            @PathVariable String entityType,
            @PathVariable String entityId) {
        List<FileEntity> files = fileService.getFilesByEntity(entityType, entityId);
        return ApiResponse.success(files.stream().map(this::toDto).toList());
    }

    /**
     * 获取实体指定字段关联的文件
     */
    @GetMapping("/relation/{entityType}/{entityId}/{fieldName}")
    public ApiResponse<List<FileUploadResponseDTO>> getEntityFieldFiles(
            @PathVariable String entityType,
            @PathVariable String entityId,
            @PathVariable String fieldName) {
        List<FileEntity> files = fileService.getFilesByEntityAndField(entityType, entityId, fieldName);
        return ApiResponse.success(files.stream().map(this::toDto).toList());
    }
    
    /**
     * File download — delegates to StorageProvider which validates path traversal.
     * TenantLineInterceptor ensures the file belongs to the current tenant.
     */
    @GetMapping("/download/{fileId}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileId) {
        FileEntity fileEntity = fileService.getFileById(fileId);
        if (fileEntity == null) {
            return ResponseEntity.notFound().build();
        }

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
