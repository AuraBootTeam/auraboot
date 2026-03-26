package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 动态导出请求DTO
 * 
 * @author AuraBoot Framework
 * @since 1.0.0
 */
@Data
public class DynamicExportRequest {
    
    /**
     * 页面ID
     */
    @NotBlank(message = "页面ID不能为空")
    private String pageId;
    
    /**
     * 导出格式（excel, csv, json）
     */
    private String format = "excel";
    
    /**
     * 导出的字段列表（为空则导出所有字段）
     */
    private List<String> fields;
    
    /**
     * 查询条件
     */
    private Map<String, Object> conditions;
    
    /**
     * 排序字段
     */
    private String sortBy;
    
    /**
     * 排序方向（asc, desc）
     */
    private String sortOrder = "asc";
    
    /**
     * 最大导出数量
     */
    private Integer maxRows = 10000;
    
    /**
     * 文件名
     */
    private String fileName;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> metadata;
}