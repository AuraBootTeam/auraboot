package com.auraboot.framework.file.service;

import com.auraboot.framework.file.dto.FileRelationRequestDTO;
import com.auraboot.framework.file.dto.FileUploadResponseDTO;
import com.auraboot.framework.file.entity.FileEntity;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件服务接口
 */
public interface FileService {
    
    /**
     * 上传文件
     */
    FileUploadResponseDTO uploadFile(MultipartFile file, Long userId);
    
    /**
     * 批量上传文件
     */
    List<FileUploadResponseDTO> uploadFiles(MultipartFile[] files, Long userId);
    
    /**
     * 根据文件ID获取文件信息
     */
    FileEntity getFileById(String fileId);
    
    /**
     * 根据用户ID获取文件列表
     */
    List<FileEntity> getFilesByUserId(Long userId);
    
    /**
     * 删除文件
     */
    boolean deleteFile(String fileId, Long userId);
    
    /**
     * 批量删除文件
     */
    boolean deleteFiles(String[] fileIds, Long userId);
    
    /**
     * 建立文件关联关系
     */
    boolean createFileRelation(FileRelationRequestDTO request);
    
    /**
     * 获取实体关联的文件列表
     */
    List<FileEntity> getFilesByEntity(String entityType, String entityId);
    
    /**
     * 获取实体指定字段关联的文件列表
     */
    List<FileEntity> getFilesByEntityAndField(String entityType, String entityId, String fieldName);
    
    /**
     * 移除文件关联关系
     */
    boolean removeFileRelation(String entityType, String entityId, String fieldName);
    
    /**
     * 获取文件下载URL
     */
    String getFileDownloadUrl(String fileId);
    
    /**
     * 根据业务ID查询文件
     * @param pid 业务ID
     * @return 文件信息
     */
    FileEntity findByPid(String pid);
}