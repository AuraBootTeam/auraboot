package com.auraboot.framework.file.entity;

import com.auraboot.framework.file.constant.StorageType;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.Instant;

/**
 * 文件信息实体
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("ns_files")
public class FileEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文件唯一标识UUID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    
    /**
     * 业务ID(ULID)
     */
    @TableField("pid")
    private String pid;

    /**
     * 存储文件名
     */
    @TableField("file_name")
    private String fileName;

    /**
     * 原始文件名
     */
    @TableField("original_name")
    private String originalName;

    /**
     * 文件大小(字节)
     */
    @TableField("file_size")
    private Long fileSize;

    /**
     * 文件MIME类型
     */
    @TableField("mime_type")
    private String mimeType;

    /**
     * 文件扩展名
     */
    @TableField("file_extension")
    private String fileExtension;

    /**
     * 存储类型
     */
    @TableField("storage_type")
    private StorageType storageType;

    /**
     * 本地存储路径
     */
    @TableField("local_path")
    private String localPath;

    /**
     * 云存储路径
     */
    @TableField("cloud_path")
    private String cloudPath;

    /**
     * 云存储桶名
     */
    @TableField("cloud_bucket")
    private String cloudBucket;

    /**
     * 云存储对象键
     */
    @TableField("cloud_key")
    private String cloudKey;

    /**
     * 云存储区域
     */
    @TableField("cloud_region")
    private String cloudRegion;

    /**
     * 上传时间
     */
    @TableField(value = "upload_time", fill = FieldFill.INSERT)
    private Instant uploadTime;

    /**
     * 上传用户ID
     */
    @TableField("created_by")
    private Long createdBy;

    /**
     * 文件状态
     */
    @TableField("status")
    private String status; // 'active' | 'deleted'

    /**
     * 创建时间
     */
    @TableField(value = "created_time", fill = FieldFill.INSERT)
    private Instant createdTime;

    /**
     * 更新时间
     */
    @TableField(value = "updated_time", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedTime;

    /**
     * 删除标记
     */
    @TableField("deleted_flag")
    @TableLogic(value = "false", delval = "true")
    private Boolean deletedFlag;
    
    // 手动添加setter/getter方法以解决Lombok编译问题
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }
    
    public String getFileName() {
        return fileName;
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
    
    public String getLocalPath() {
        return localPath;
    }
    
    public void setCloudPath(String cloudPath) {
        this.cloudPath = cloudPath;
    }
    
    public void setStorageType(StorageType storageType) {
        this.storageType = storageType;
    }
    
    public void setFileExtension(String fileExtension) {
        this.fileExtension = fileExtension;
    }
    
    public void setUploadTime(Instant uploadTime) {
        this.uploadTime = uploadTime;
    }
    
    public void setCreatedTime(Instant createdTime) {
        this.createdTime = createdTime;
    }
    
    public void setUpdatedTime(Instant updatedTime) {
        this.updatedTime = updatedTime;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
    
    public void setDeletedFlag(Boolean deletedFlag) {
        this.deletedFlag = deletedFlag;
    }
    
    public String getPid() {
        return pid;
    }
    
    public void setPid(String pid) {
        this.pid = pid;
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
    
    public String getCloudPath() {
        return cloudPath;
    }
    
    public StorageType getStorageType() {
        return storageType;
    }
    
    public String getStatus() {
        return status;
    }
    
    public Instant getCreatedTime() {
        return createdTime;
    }
    
    public Long getId() {
        return id;
    }

}