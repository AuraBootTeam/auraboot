package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 模型验证结果
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class ModelValidationResult {
    
    /**
     * 验证是否通过
     */
    private Boolean valid;
    
    /**
     * 模型编码
     */
    private String modelCode;
    
    /**
     * 验证错误列表
     */
    private List<ValidationError> errors;
    
    /**
     * 验证警告列表
     */
    private List<ValidationWarning> warnings;
    
    /**
     * 验证消息
     */
    private String message;
    
    @Data
    @Builder
    public static class ValidationError {
        private String field;
        private String code;
        private String message;
    }
    
    @Data
    @Builder
    public static class ValidationWarning {
        private String field;
        private String code;
        private String message;
    }
}