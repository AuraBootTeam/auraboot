package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 查询验证结果
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class QueryValidationResult {
    
    /**
     * 是否有效
     */
    private boolean valid;
    
    /**
     * 错误消息
     */
    private String errorMessage;
    
    /**
     * 警告消息
     */
    private String warningMessage;
    
    /**
     * 创建有效结果
     */
    public static QueryValidationResult valid() {
        return QueryValidationResult.builder()
                .valid(true)
                .build();
    }
    
    /**
     * 创建无效结果
     */
    public static QueryValidationResult invalid(String errorMessage) {
        return QueryValidationResult.builder()
                .valid(false)
                .errorMessage(errorMessage)
                .build();
    }
    
    /**
     * 创建带警告的有效结果
     */
    public static QueryValidationResult validWithWarning(String warningMessage) {
        return QueryValidationResult.builder()
                .valid(true)
                .warningMessage(warningMessage)
                .build();
    }
}