package com.auraboot.framework.file.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件信息请求DTO
 */
@Data
public class FileInfoRequestDTO {
    
    /**
     * 存储文件名
     */
    @NotBlank(message = "文件名不能为空")
    private String fileName;
    
    /**
     * 原始文件名
     */
    @NotBlank(message = "原始文件名不能为空")
    private String originalName;
    
    /**
     * 文件大小(字节)
     */
    @NotNull(message = "文件大小不能为空")
    private Long fileSize;
    
    /**
     * 文件MIME类型
     */
    private String mimeType;
    
    /**
     * 本地存储路径
     */
    private String localPath;
    
    /**
     * 云存储路径
     */
    private String cloudPath;
    
    /**
     * 存储类型
     */
    @NotBlank(message = "存储类型不能为空")
    private String storageType;
    
    /**
     * 上传时间
     */
    private LocalDateTime uploadTime;
    
    // 手动添加getter方法以解决Lombok编译问题
    public String getFileName() {
        return fileName;
    }
    
    public String getOriginalName() {
        return originalName;
    }
    
    public Long getFileSize() {
        return fileSize;
    }
    
    public String getMimeType() {
        return mimeType;
    }
    
    public String getLocalPath() {
        return localPath;
    }
    
    public String getCloudPath() {
        return cloudPath;
    }
    
    public String getStorageType() {
        return storageType;
    }
    
    public LocalDateTime getUploadTime() {
        return uploadTime;
    }
}