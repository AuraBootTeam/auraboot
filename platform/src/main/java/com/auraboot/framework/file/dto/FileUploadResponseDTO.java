package com.auraboot.framework.file.dto;

import com.auraboot.framework.file.constant.StorageType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件上传响应DTO
 */
@Data
public class FileUploadResponseDTO {
    /**
     * 文件ID
     */
    private String fileId;
    
    /**
     * 存储文件名
     */
    private String fileName;
    
    /**
     * 原始文件名
     */
    private String originalName;
    
    /**
     * 文件大小
     */
    private Long fileSize;
    
    /**
     * MIME类型
     */
    private String mimeType;
    
    /**
     * 本地路径
     */
    private String localPath;
    
    /**
     * 云存储路径
     */
    private String cloudPath;
    
    /**
     * 存储类型
     */
    private StorageType storageType;
    
    /**
     * 上传时间
     */
    private LocalDateTime uploadTime;
    
    /**
     * 创建用户
     */
    private Long createdBy;
    
    /**
     * 文件状态
     */
    private String status;
    
    /**
     * 访问URL
     */
    private String url;
    
    // 手动添加setter方法以解决Lombok编译问题
    public void setFileId(String fileId) {
        this.fileId = fileId;
    }
    
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }
    
    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }
    
    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }
    
    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }
    
    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }
    
    public void setCloudPath(String cloudPath) {
        this.cloudPath = cloudPath;
    }
    
    public void setStorageType(StorageType storageType) {
        this.storageType = storageType;
    }
    
    public void setUploadTime(LocalDateTime uploadTime) {
        this.uploadTime = uploadTime;
    }
    
    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public void setUrl(String url) {
        this.url = url;
    }

}

