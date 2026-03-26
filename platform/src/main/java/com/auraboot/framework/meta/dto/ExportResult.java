package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;

/**
 * 导出结果
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class ExportResult {
    
    /**
     * 导出是否成功
     */
    private Boolean success;
    
    /**
     * 导出文件路径
     */
    private String filePath;
    
    /**
     * 下载URL
     */
    private String downloadUrl;
    
    /**
     * 导出的记录数
     */
    private Long recordCount;
    
    /**
     * 文件大小（字节）
     */
    private Long fileSize;
    
    /**
     * 导出格式
     */
    private String format;
    
    /**
     * 导出时间
     */
    private Instant exportTime;
    
    /**
     * 错误信息
     */
    private String errorMessage;
}