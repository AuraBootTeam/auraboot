package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 动态导入请求DTO
 * 
 * @author AuraBoot Framework
 * @since 1.0.0
 */
@Data
public class DynamicImportRequest {
    
    /**
     * 页面ID
     */
    @NotBlank(message = "页面ID不能为空")
    private String pageId;
    
    /**
     * 导入数据列表
     */
    @NotEmpty(message = "导入数据不能为空")
    private List<Map<String, Object>> dataList;
    
    /**
     * 导入模式（insert, update, upsert）
     */
    private String mode = "insert";
    
    /**
     * 是否验证数据
     */
    private Boolean validate = true;
    
    /**
     * 是否忽略错误继续处理
     */
    private Boolean ignoreErrors = false;
    
    /**
     * 更新时的匹配字段（用于update和upsert模式）
     */
    private List<String> matchFields;
    
    /**
     * 是否跳过重复数据
     */
    private Boolean skipDuplicates = false;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> metadata;
}