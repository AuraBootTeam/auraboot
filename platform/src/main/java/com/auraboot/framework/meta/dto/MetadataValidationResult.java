package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 元数据验证结果
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class MetadataValidationResult {
    
    /**
     * 验证是否通过
     */
    private Boolean valid;
    
    /**
     * 验证的模型编码
     */
    private String modelCode;
    
    /**
     * 验证错误列表
     */
    private List<String> errors;
    
    /**
     * 验证警告列表
     */
    private List<String> warnings;
    
    /**
     * 验证摘要
     */
    private String summary;
}