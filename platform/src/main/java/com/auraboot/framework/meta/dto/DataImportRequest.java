package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 数据导入请求
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class DataImportRequest {
    
    /**
     * 导入文件路径
     */
    private String filePath;
    
    /**
     * 导入格式
     */
    @Builder.Default
    private ImportFormat format = ImportFormat.EXCEL;
    
    /**
     * 字段映射
     */
    private Map<String, String> fieldMapping;
    
    /**
     * 是否跳过第一行（表头）
     */
    @Builder.Default
    private Boolean skipFirstRow = true;
    
    /**
     * 导入模式
     */
    @Builder.Default
    private ImportMode mode = ImportMode.INSERT;
    
    /**
     * 批量大小
     */
    @Builder.Default
    private Integer batchSize = 1000;
    
    /**
     * 验证规则
     */
    private List<String> validationRules;
    
    /**
     * 扩展参数
     */
    private Map<String, Object> extraParams;
    
    public enum ImportFormat {
        EXCEL, CSV, JSON, XML
    }
    
    public enum ImportMode {
        INSERT,         // 仅插入
        UPDATE,         // 仅更新
        UPSERT,         // 插入或更新
        REPLACE         // 替换
    }
}